/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage.memory
// scalastyle:off
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkConf, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.{UNROLL_MEMORY_CHECK_PERIOD, UNROLL_MEMORY_GROWTH_FACTOR}
import org.apache.spark.memory.{MemoryManager, MemoryMode}
import org.apache.spark.serializer.{SerializationStream, SerializerManager}
import org.apache.spark.storage._
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.{SizeEstimator, Utils}
import org.apache.spark.util.collection.SizeTrackingVector
import org.apache.spark.util.io.{ChunkedByteBuffer, ChunkedByteBufferOutputStream}

// 内存中的Block抽象
private sealed trait MemoryEntry[T] {
  def size: Long  // 当前Block大小
  def memoryMode: MemoryMode // 内存模式
  def classTag: ClassTag[T] // Block类型
}
// 反序列化后的MemoryEntry
private case class DeserializedMemoryEntry[T](
    value: Array[T],
    size: Long,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  val memoryMode: MemoryMode = MemoryMode.ON_HEAP
}
// 序列化后的MemoryEntry
private case class SerializedMemoryEntry[T](
    buffer: ChunkedByteBuffer,
    memoryMode: MemoryMode,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  def size: Long = buffer.size
}

private[storage] trait BlockEvictionHandler {
  /**
   * 从内存中删除Block,如果可以会存储磁盘.当内从达到限制的并且需要释放空间时调用.
    * 如果数据不在磁盘上,那么不会创建.(我也不太明白).
    * 本方法调用者在调用之前必须持有block上的写锁,该方法并不释放写锁.<br>
    * Drop a block from memory, possibly putting it on disk if applicable. Called when the memory
   * store reaches its limit and needs to free up space.
   *
   * If `data` is not put on disk, it won't be created.
   *
   * The caller of this method must hold a write lock on the block before calling this method.
   * This method does not release the write lock.
   *
   * @return the block's new effective StorageLevel.
   */
  private[storage] def dropFromMemory[T: ClassTag](
      blockId: BlockId,
      data: () => Either[Array[T], ChunkedByteBuffer]): StorageLevel
}

/**
 * 将block存储到内存中,可以是反序列化的Java对象数组,也可以是序列化的ByteBuffer.
  * Stores blocks in memory, either as Arrays of deserialized Java objects or as
 * serialized ByteBuffers.
 */
private[spark] class MemoryStore(
    conf: SparkConf,
    blockInfoManager: BlockInfoManager, // Block信息管理
    serializerManager: SerializerManager, // 序列化管理
    memoryManager: MemoryManager,         // 内存管理
    blockEvictionHandler: BlockEvictionHandler) // block移除处理器
  extends Logging {
  // 所有的内存位置改变,如果存入block,移除block,获取或释放展开内存,都需要锁住memorymanager
  // Note: all changes to memory allocations, notably putting blocks, evicting blocks, and
  // acquiring or releasing unroll memory, must be synchronized on `memoryManager`!
  /** 内存中BlockId和MemoryEntry之间的映射关系缓存*/
  private val entries = new LinkedHashMap[BlockId, MemoryEntry[_]](32, 0.75f, true)
  /** 任务尝试线程的标识TaskAttemptId与任务尝试线程在对内存展开的所有block占用的内存大小(字节)之和之间的映射map*/
  // A mapping from taskAttemptId to amount of memory used for unrolling a block (in bytes)
  // All accesses of this map are assumed to have manually synchronized on `memoryManager`
  private val onHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()
  /** 任务尝试线程标识TaskAttemptId和任务尝试线程在堆外内存展开的所有Block占用内存大小之和的map
    * 堆外展开内存知用于putIteratorAsBytes()方法,因为堆外内存总是存储序列化值.
    * */
  // Note: off-heap unroll memory is only used in putIteratorAsBytes() because off-heap caching
  // always stores serialized values.
  private val offHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()
  /** 用来展开任何Block之前初始内存大小,可通过spark.storage.unrollMemoryThreshold配置,默认1mb*/
  // Initial memory to request before unrolling any block
  private val unrollMemoryThreshold: Long =
    conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024)

  /**
    * 可用于存储的内存总量,单位字节.
    * Total amount of memory available for storage, in bytes. */
  private def maxMemory: Long = {
    memoryManager.maxOnHeapStorageMemory + memoryManager.maxOffHeapStorageMemory
  }

  if (maxMemory < unrollMemoryThreshold) {
    logWarning(s"Max memory ${Utils.bytesToString(maxMemory)} is less than the initial memory " +
      s"threshold ${Utils.bytesToString(unrollMemoryThreshold)} needed to store a block in " +
      s"memory. Please configure Spark with more memory.")
  }

  logInfo("MemoryStore started with capacity %s".format(Utils.bytesToString(maxMemory)))

  /**
    * 包括展开内存在内的已使用存储内存大小,单位字节.
    * Total storage memory used including unroll memory, in bytes. */
  private def memoryUsed: Long = memoryManager.storageMemoryUsed

  /**
   * 存储内存总量,单位字节,用于缓存block.这不包含展开内存.
    * Amount of storage memory, in bytes, used for caching blocks.
   * This does not include memory used for unrolling.
   */
  private def blocksMemoryUsed: Long = memoryManager.synchronized {
    memoryUsed - currentUnrollMemory
  }
  // 用于获取BlockId对应的MemoryEntry
  def getSize(blockId: BlockId): Long = {
    entries.synchronized {
      entries.get(blockId).size
    }
  }

  /**
   * 将BlockId对应的Block写入内存.使用size参数来测试MemoryStore中是否有足够的空间.如果有,创建一个ByteBuffer
    * 并且将其放入MemoryStore.否则,不会创建ByteBuffer.调用者需要确保size参数是正确的.
    * Use `size` to test if there is enough space in MemoryStore. If so, create the ByteBuffer and
   * put it into MemoryStore. Otherwise, the ByteBuffer won't be created.
   *
   * The caller should guarantee that `size` is correct.
   *
   * @return true if the put() succeeded, false otherwise.
   */
  def putBytes[T: ClassTag](
      blockId: BlockId,
      size: Long,
      memoryMode: MemoryMode,
      _bytes: () => ChunkedByteBuffer): Boolean = {
    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")
    // 从MemoryManager中获取BlockId对应block的逻辑内存
    // if获取失败
    if (memoryManager.acquireStorageMemory(blockId, size, memoryMode)) {
      // 调用_bytes()获取Block数据
      // We acquired enough memory for the block, so go ahead and put it
      val bytes = _bytes()
      assert(bytes.size == size)
      // 创建Block对应的SerializedMemoryEntry.
      val entry = new SerializedMemoryEntry[T](bytes, memoryMode, implicitly[ClassTag[T]])
      entries.synchronized {
        // 放入entries缓存
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      // 返回
      true
    } else {
      false
    }
  }

  /**
   *

   * 将指定Block(先转成iterator)存入内存.iterator有可能非常大,一次性写入可能会导致内存溢出.
   * 首先将Block转换成iterator,然后逐渐展开此iterator,并且周期性检查展开内存是否足够.
   * Attempt to put the given block in memory store as bytes.
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * @return in case of success, the estimated size of the stored data. In case of failure, return
   *         an iterator containing the values of the block. The returned iterator will be backed
   *         by the combination of the partially-unrolled block and the remaining elements of the
   *         original input iterator. The caller must either fully consume this iterator or call
   *         `close()` on it in order to free the storage memory consumed by the partially-unrolled
   *         block.
   */
  private[storage] def putIteratorAsValues[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T]): Either[PartiallyUnrolledIterator[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")
    // 已经展开的元素数量
    // Number of elements unrolled so far
    var elementsUnrolled = 0
    // MemoryStore是否仍然有足够的内存
    // Whether there is still enough memory for us to continue unrolling this block
    var keepUnrolling = true
    // 用来展开Block之前,初始请求内存大小
    // Initial per-task memory to request for unrolling blocks (bytes).
    val initialMemoryThreshold = unrollMemoryThreshold
    // 定期检查是否需要更多内存的时间间隔
    // How often to check whether we need to request more memory
    val memoryCheckPeriod = conf.get(UNROLL_MEMORY_CHECK_PERIOD)
    // 当前任务用于展开Block所保留的内存
    // Memory currently reserved by this task for this particular unrolling operation
    var memoryThreshold = initialMemoryThreshold
    // 展开内存不充足时,请求增长的因子
    // Memory to request as a multiple of current vector size
    val memoryGrowthFactor = conf.get(UNROLL_MEMORY_GROWTH_FACTOR)
    // Block已经使用的展开内存大小.
    // Keep track of unroll memory used by this particular block / putIterator() operation
    var unrollMemoryUsedByThisBlock = 0L
    // 用于追踪Block(iterator)每次迭代的数据
    // Underlying vector for unrolling the block
    var vector = new SizeTrackingVector[T]()(classTag)
    // 请求足够内存用于开始展开
    // Request enough memory to begin unrolling
    keepUnrolling =
      reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, MemoryMode.ON_HEAP)
    // 如果false,log一下
    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else {
      // 为unrollMemoryUsedByThisBlock设置初始值,即局部变量initialMemoryThreshold
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }
    // 安全地展开Block,检查我们是否定期超过我们的阈值
    // Unroll this block safely, checking whether we have exceeded our threshold periodically
    // 迭代器中仍有元素,并且keepUnrolling为true,不断遍历
    while (values.hasNext && keepUnrolling) {
      // 将数据放入追踪器vector中,并且周期性地检查vector中所有数据的估算大小currentSize
      // 是否超过memoryThreshold.
      vector += values.next()
      if (elementsUnrolled % memoryCheckPeriod == 0) {
        // 如果vector中的大小超过threshold,请求更多内存
        // If our vector's size has exceeded the threshold, request more memory
        val currentSize = vector.estimateSize()
        if (currentSize >= memoryThreshold) {
          val amountToRequest = (currentSize * memoryGrowthFactor - memoryThreshold).toLong
          // 请求内存
          keepUnrolling =
            reserveUnrollMemoryForThisTask(blockId, amountToRequest, MemoryMode.ON_HEAP)
          // 如果申请到内存,更新unrollMemoryUsedByThisBlock大小.
          if (keepUnrolling) {
            unrollMemoryUsedByThisBlock += amountToRequest
          }
          // 更新threshold,
          // New threshold is currentSize * memoryGrowthFactor
          memoryThreshold += amountToRequest
        }
      }
      elementsUnrolled += 1
    }
    // 如果展开了Iterator所有数据后,keepUnrolling为true,说明Block申请到了足够的保留内存.
    if (keepUnrolling) {
      // We successfully unrolled the entirety of this block
      // 这里就是申请足够内存的情况
      // 转换成Array
      val arrayValues = vector.toArray
      vector = null // 取消vector引用
      // 创建DeserializedMemoryEntry
      val entry =
        new DeserializedMemoryEntry[T](arrayValues, SizeEstimator.estimate(arrayValues), classTag)
      val size = entry.size // 重新计算vector大小
      // 将展开Block转换成存储
      def transferUnrollToStorage(amount: Long): Unit = {
        // 锁住memoryManager
        // Synchronize so that transfer is atomic
        memoryManager.synchronized {
          // 释放该任务的展开内存
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, amount)
          // 为Block获取内存空间
          val success = memoryManager.acquireStorageMemory(blockId, amount, MemoryMode.ON_HEAP)
          //  如果没成功,抛出异常
          assert(success, "transferring unroll memory to storage memory failed")
        }
      }
      // 如果需要的话,申请存储内存
      // Acquire storage memory if necessary to store this block in memory.
      val enoughStorageMemory = {
        if (unrollMemoryUsedByThisBlock <= size) {
          // 如果本任务的展开内存小于size,说明还需要申请内存.
          val acquiredExtra =
            memoryManager.acquireStorageMemory(
              blockId, size - unrollMemoryUsedByThisBlock, MemoryMode.ON_HEAP)
          if (acquiredExtra) {
            // 申请到的话,就开始转换
            transferUnrollToStorage(unrollMemoryUsedByThisBlock)
          }
          // enoughStorageMemory=acquiredExtra
          acquiredExtra
        } else { // unrollMemoryUsedByThisBlock > size
          // If this task attempt already owns more unroll memory than is necessary to store the
          // block, then release the extra memory that will not be used.
          // 如果大于size,说明申请内存过多,归还一部分内存
          val excessUnrollMemory = unrollMemoryUsedByThisBlock - size
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, excessUnrollMemory)
          // 转换
          transferUnrollToStorage(size)
          true
        }
      }
      // 如果内存足够
      if (enoughStorageMemory) {
        // 将BlockId与entry映射关系放入entries缓存
        entries.synchronized {
          entries.put(blockId, entry)
        }
        logInfo("Block %s stored as values in memory (estimated size %s, free %s)".format(
          blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
        // 返回Right(size).Right是Either的一个子样例类.调用Either的isRight返回true.意思有值.
        Right(size)
      } else {
        // 如果currentUnrollMemoryForThisTask>=unrollMemoryUsedByThisBlock,扔异常
        assert(currentUnrollMemoryForThisTask >= unrollMemoryUsedByThisBlock,
          "released too much unroll memory")
        // 没有足够内存,创建PartiallyUnrolledIterator,返回left.
        Left(new PartiallyUnrolledIterator(
          this,
          MemoryMode.ON_HEAP,
          unrollMemoryUsedByThisBlock,
          unrolled = arrayValues.toIterator,
          rest = Iterator.empty))
      }
    } else {
      // 这一步就说名展开Iterator中所有数据后keepUnrooling返回false,
      // 这意味着没有申请到足够保留内存
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, vector.estimateSize())
      // 创建PartiallyUnrolledIterator,返回left,left是Either的子样例类,可以当做失败.
      Left(new PartiallyUnrolledIterator(
        this,
        MemoryMode.ON_HEAP,
        unrollMemoryUsedByThisBlock,
        unrolled = vector.iterator,
        rest = values))
    }
  }

  /**
   * 将指定Block(先转成iterator)存入内存.iterator有可能非常大,一次性写入可能会导致内存溢出.
    * 首先将Block转换成iterator,然后逐渐展开此iterator,并且周期性检查展开内存是否足够.
    * Attempt to put the given block in memory store as bytes.
   *
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * @return in case of success, the estimated size of the stored data. In case of failure,
   *         return a handle which allows the caller to either finish the serialization by
   *         spilling to disk or to deserialize the partially-serialized block and reconstruct
   *         the original input iterator. The caller must either fully consume this result
   *         iterator or call `discard()` on it in order to free the storage memory consumed by the
   *         partially-unrolled block.
   */
  private[storage] def putIteratorAsBytes[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T],
      memoryMode: MemoryMode): Either[PartiallySerializedBlock[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")

    val allocator = memoryMode match {
      case MemoryMode.ON_HEAP => ByteBuffer.allocate _
      case MemoryMode.OFF_HEAP => Platform.allocateDirectBuffer _
    }
    // MemoryStore是否仍然有足够的内存
    // Whether there is still enough memory for us to continue unrolling this block
    var keepUnrolling = true
    // 已经展开的元素数量
    // Number of elements unrolled so far
    var elementsUnrolled = 0L
    // 定期检查是否需要更多内存的时间间隔
    // How often to check whether we need to request more memory
    val memoryCheckPeriod = conf.get(UNROLL_MEMORY_CHECK_PERIOD)
    // 展开内存不充足时,请求增长的因子
    // Memory to request as a multiple of current bbos size
    val memoryGrowthFactor = conf.get(UNROLL_MEMORY_GROWTH_FACTOR)
    // 初始化每个任务的内存用于请求展开block
    // Initial per-task memory to request for unrolling blocks (bytes).
    val initialMemoryThreshold = unrollMemoryThreshold
    // Block已经使用的展开内存大小
    // Keep track of unroll memory used by this particular block / putIterator() operation
    var unrollMemoryUsedByThisBlock = 0L
    // 展开Block的底层缓冲
    // Underlying buffer for unrolling the block
    val redirectableStream = new RedirectableOutputStream
    // 分块大小.min(initialMemoryThreshold,Int.MaxValue)
    val chunkSize = if (initialMemoryThreshold > Int.MaxValue) {
      logWarning(s"Initial memory threshold of ${Utils.bytesToString(initialMemoryThreshold)} " +
        s"is too large to be set as chunk size. Chunk size has been capped to " +
        s"${Utils.bytesToString(Int.MaxValue)}")
      Int.MaxValue
    } else {
      initialMemoryThreshold.toInt
    }
    // 创建ChunkedByteBufferOutputStream
    val bbos = new ChunkedByteBufferOutputStream(chunkSize, allocator)
    // 为redirectableStream设置输出流
    redirectableStream.setOutputStream(bbos)
    val serializationStream: SerializationStream = {
      val autoPick = !blockId.isInstanceOf[StreamBlockId]
      val ser = serializerManager.getSerializer(classTag, autoPick).newInstance()
      ser.serializeStream(serializerManager.wrapForCompression(blockId, redirectableStream))
    }

    // 请求开始展开时要使用的内存
    // Request enough memory to begin unrolling
    keepUnrolling = reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, memoryMode)
    // 如果开始请求内存失败
    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else {
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }

    def reserveAdditionalMemoryIfNecessary(): Unit = {
      if (bbos.size > unrollMemoryUsedByThisBlock) {
        val amountToRequest = (bbos.size * memoryGrowthFactor - unrollMemoryUsedByThisBlock).toLong
        keepUnrolling = reserveUnrollMemoryForThisTask(blockId, amountToRequest, memoryMode)
        if (keepUnrolling) {
          unrollMemoryUsedByThisBlock += amountToRequest
        }
      }
    }

    // Unroll this block safely, checking whether we have exceeded our threshold
    while (values.hasNext && keepUnrolling) {
      serializationStream.writeObject(values.next())(classTag)
      elementsUnrolled += 1
      if (elementsUnrolled % memoryCheckPeriod == 0) {
        reserveAdditionalMemoryIfNecessary()
      }
    }

    // Make sure that we have enough memory to store the block. By this point, it is possible that
    // the block's actual memory usage has exceeded the unroll memory by a small amount, so we
    // perform one final call to attempt to allocate additional memory if necessary.
    if (keepUnrolling) {
      serializationStream.close()
      if (bbos.size > unrollMemoryUsedByThisBlock) {
        val amountToRequest = bbos.size - unrollMemoryUsedByThisBlock
        keepUnrolling = reserveUnrollMemoryForThisTask(blockId, amountToRequest, memoryMode)
        if (keepUnrolling) {
          unrollMemoryUsedByThisBlock += amountToRequest
        }
      }
    }

    if (keepUnrolling) {
      val entry = SerializedMemoryEntry[T](bbos.toChunkedByteBuffer, memoryMode, classTag)
      // Synchronize so that transfer is atomic
      memoryManager.synchronized {
        releaseUnrollMemoryForThisTask(memoryMode, unrollMemoryUsedByThisBlock)
        val success = memoryManager.acquireStorageMemory(blockId, entry.size, memoryMode)
        assert(success, "transferring unroll memory to storage memory failed")
      }
      entries.synchronized {
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(entry.size),
        Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      Right(entry.size)
    } else {
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, bbos.size)
      Left(
        new PartiallySerializedBlock(
          this,
          serializerManager,
          blockId,
          serializationStream,
          redirectableStream,
          unrollMemoryUsedByThisBlock,
          memoryMode,
          bbos,
          values,
          classTag))
    }
  }
  /** 从内存中读取BlockId对应的Block*/
  def getBytes(blockId: BlockId): Option[ChunkedByteBuffer] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: DeserializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getBytes on serialized blocks")
      case SerializedMemoryEntry(bytes, _, _) => Some(bytes)
    }
  }
  /** 从内存中读取BlockId对应的Block,封装成Iterator*/
  def getValues(blockId: BlockId): Option[Iterator[_]] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: SerializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getValues on deserialized blocks")
      case DeserializedMemoryEntry(values, _, _) =>
        val x = Some(values)
        x.map(_.iterator)
    }
  }
  /** 从内存中移除BlockId对应的Block*/
  def remove(blockId: BlockId): Boolean = memoryManager.synchronized {
    // 缓存中移除
    val entry = entries.synchronized {
      entries.remove(blockId)
    }
    if (entry != null) {
      entry match {
          // 清理buffer
        case SerializedMemoryEntry(buffer, _, _) => buffer.dispose()
        case _ =>
      }
      // 释放内存
      memoryManager.releaseStorageMemory(entry.size, entry.memoryMode)
      logDebug(s"Block $blockId of size ${entry.size} dropped " +
        s"from memory (free ${maxMemory - blocksMemoryUsed})")
      true
    } else {
      false
    }
  }
  /** 清空memoryStore*/
  def clear(): Unit = memoryManager.synchronized {
    entries.synchronized {
      entries.clear()
    }
    onHeapUnrollMemoryMap.clear()
    offHeapUnrollMemoryMap.clear()
    memoryManager.releaseAllStorageMemory()
    logInfo("MemoryStore cleared")
  }

  /**
   * 根据给定的BlockId返回RDDId,如果不是RDD返回NONE
    * Return the RDD ID that a given block ID is from, or None if it is not an RDD block.
   */
  private def getRddId(blockId: BlockId): Option[Int] = {
    blockId.asRDDId.map(_.rddId)
  }

  /**
   * 移除一部分Block来释放指定大小空间来存储指定Block.如果block大于我们的内存或者需要从
    * 相同的RDD中替换另一个block会导致失败.(相同RDD中替换会导致一种浪费的循环替换模式,
    * 所以尽量避免)
    * Try to evict blocks to free up a given amount of space to store a particular block.
   * Can fail if either the block is bigger than our memory or it would require replacing
   * another block from the same RDD (which leads to a wasteful cyclic replacement pattern for
   * RDDs that don't fit into memory that we want to avoid).
   *
   * @param blockId 要存储的Block的Id.
    *                the ID of the block we are freeing space for, if any
   * @param space 需要移除Block腾出的空间大小.
    *              the size of this block
   * @param memoryMode 存储需要的内存模式
    *                   the type of memory to free (on- or off-heap)
   * @return the amount of memory (in bytes) freed by eviction
   */
  private[spark] def evictBlocksToFreeSpace(
      blockId: Option[BlockId], //
      space: Long,
      memoryMode: MemoryMode): Long = {
    assert(space > 0)
    memoryManager.synchronized {
      var freedMemory = 0L // 已经释放的内存大小
      val rddToAdd = blockId.flatMap(getRddId) // 需要添加的RDD的RDDBlockId标记
      val selectedBlocks = new ArrayBuffer[BlockId]  // 创建要被移除的Block数组
      def blockIsEvictable(blockId: BlockId, entry: MemoryEntry[_]): Boolean = {
        // 是否可移除,memoryMode为true,并且(rddToAdd是空或rddToAdd不等于Block对应的RDD,防止循环替换)
        entry.memoryMode == memoryMode && (rddToAdd.isEmpty || rddToAdd != getRddId(blockId))
      }
      // This is synchronized to ensure that the set of entries is not changed
      // (because of getValue or getBytes) while traversing the iterator, as that
      // can lead to exceptions.
      // synchronized保证迭代时候键值对不被改变
      entries.synchronized {
        val iterator = entries.entrySet().iterator()
        // 已释放内存小于需要释放内存
        while (freedMemory < space && iterator.hasNext) {
          val pair = iterator.next()
          val blockId = pair.getKey
          val entry = pair.getValue
          // 如果Block是可移除的
          if (blockIsEvictable(blockId, entry)) {
            // 我们不想驱逐当前正在读取的Block，因此我们需要获得对驱逐候选block的独占写锁.
            // 我们在这里执行非阻塞“tryLock”以忽略被锁定以供读取的Blcok
            // We don't want to evict blocks which are currently being read, so we need to obtain
            // an exclusive write lock on blocks which are candidates for eviction. We perform a
            // non-blocking "tryLock" here in order to ignore blocks which are locked for reading:
            // 获得写锁
            if (blockInfoManager.lockForWriting(blockId, blocking = false).isDefined) {
              // 添加到选中Block数组
              selectedBlocks += blockId
              // 释放空间+=Block的size
              freedMemory += pair.getValue.size
            }
          }
        }
      }
      // 删除Block方法
      def dropBlock[T](blockId: BlockId, entry: MemoryEntry[T]): Unit = {
        // 确定Block存储类型
        val data = entry match {
          case DeserializedMemoryEntry(values, _, _) => Left(values)
          case SerializedMemoryEntry(buffer, _, _) => Right(buffer)
        }
        // 有效的存储级别
        val newEffectiveStorageLevel =
          blockEvictionHandler.dropFromMemory(blockId, () => data)(entry.classTag)
        if (newEffectiveStorageLevel.isValid) {
          // 如果newEffectiveStorageLevel有效
          // 该Block仍然存在于至少一个存储中，因此释放锁定但不删除Block信息
          // The block is still present in at least one store, so release the lock
          // but don't delete the block info
          blockInfoManager.unlock(blockId)
        } else {
          // Block不在任何存储之中,删除Block信息这样该Block可以再次被存储
          // The block isn't present in any store, so delete the block info so that the
          // block can be stored again
          blockInfoManager.removeBlock(blockId)
        }
      }
      // 如果释放的空间大于等于space,说明通过驱逐Block,已经获得了足够的空间
      if (freedMemory >= space) {
        var lastSuccessfulBlock = -1
        try {
          logInfo(s"${selectedBlocks.size} blocks selected for dropping " +
            s"(${Utils.bytesToString(freedMemory)} bytes)")
          // 遍历selectedBlocks中的每个BlockId,移除对应Block.
          (0 until selectedBlocks.size).foreach { idx =>
            val blockId = selectedBlocks(idx)
            // 根据BlockId从缓存中获取Block->MemoryEntry键值对
            val entry = entries.synchronized {
              entries.get(blockId)
            }
            // entry不可能为null,应该只有一个task删除block和entry缓存.但是为了安全起见还是检查一下
            // This should never be null as only one task should be dropping
            // blocks and removing entries. However the check is still here for
            // future safety.
            if (entry != null) {
              // 删除
              dropBlock(blockId, entry)
              afterDropAction(blockId)
            }
            // 删除成功,修改lastSuccessfulBlock,idx是selectedBlocks数组下标
            lastSuccessfulBlock = idx
          }
          logInfo(s"After dropping ${selectedBlocks.size} blocks, " +
            s"free memory is ${Utils.bytesToString(maxMemory - blocksMemoryUsed)}")
          // 删除空间大小.
          freedMemory
        } finally {
          // 像BlockManager.doPut一样，我们使用finally而不是catch来避免必须处理InterruptedException
          // like BlockManager.doPut, we use a finally rather than a catch to avoid having to deal
          // with InterruptedException
          // 如果lastSuccessfulBlock!=selectedBlocks.size - 1,说明没有完成遍历.
          if (lastSuccessfulBlock != selectedBlocks.size - 1) {
            // 处理没有成功,但是我们还持有锁,我们需要释放锁
            // the blocks we didn't process successfully are still locked, so we have to unlock them
            (lastSuccessfulBlock + 1 until selectedBlocks.size).foreach { idx =>
              val blockId = selectedBlocks(idx)
              // 释放锁
              blockInfoManager.unlock(blockId)
            }
          }
        }
      } else {
        // 释放空间小于space的话,打印一下日志,不进行存储了
        blockId.foreach { id =>
          logInfo(s"Will not store $id")
        }
        // 释放所有的锁
        selectedBlocks.foreach { id =>
          blockInfoManager.unlock(id)
        }
        0L
      }
    }
  }

  // hook for testing, so we can simulate a race
  protected def afterDropAction(blockId: BlockId): Unit = {}
  /** 本地MemoryStore中是否包含给定BlockId对应的Block*/
  def contains(blockId: BlockId): Boolean = {
    entries.synchronized { entries.containsKey(blockId) }
  }

  private def currentTaskAttemptId(): Long = {
    // In case this is called on the driver, return an invalid task attempt id.
    Option(TaskContext.get()).map(_.taskAttemptId()).getOrElse(-1L)
  }

  /**
   * 用于展开尝试execution任务给定的Block,保留指定memoryMode上指定大小的内存.
    * Reserve memory for unrolling the given block for this task.
   *
   * @return whether the request is granted.
   */
  def reserveUnrollMemoryForThisTask(
      blockId: BlockId,
      memory: Long,
      memoryMode: MemoryMode): Boolean = {
    memoryManager.synchronized {
      // 获取展开内存
      val success = memoryManager.acquireUnrollMemory(blockId, memory, memoryMode)
      // 获取成功情况
      if (success) {
        // 更新taskAttemptId与展开内存之间的映射关系.
        val taskAttemptId = currentTaskAttemptId()
        val unrollMemoryMap = memoryMode match {
          case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
          case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
        }
        unrollMemoryMap(taskAttemptId) = unrollMemoryMap.getOrElse(taskAttemptId, 0L) + memory
      }
      success // 返回
    }
  }

  /**
   * 释放任务尝试线程占用的内存.如果未指定大小，则完全删除当前任务的分配。
    * Release memory used by this task for unrolling blocks.
   * If the amount is not specified, remove the current task's allocation altogether.
   */
  def releaseUnrollMemoryForThisTask(memoryMode: MemoryMode, memory: Long = Long.MaxValue): Unit = {
    val taskAttemptId = currentTaskAttemptId()
    memoryManager.synchronized {
      // 获取内存模型对应的缓存onHeapUnrollMemoryMap
      val unrollMemoryMap = memoryMode match {
        case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
        case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
      }
      // 查看缓存是否包含taskAttemptId
      if (unrollMemoryMap.contains(taskAttemptId)) {
        // 取taskAttemptId对应内存大小和参数memory的最小值作为释放内存大小
        val memoryToRelease = math.min(memory, unrollMemoryMap(taskAttemptId))
        if (memoryToRelease > 0) {
          // 缓存中逻辑删除
          unrollMemoryMap(taskAttemptId) -= memoryToRelease
          // 释放内存
          memoryManager.releaseUnrollMemory(memoryToRelease, memoryMode)
        }
        if (unrollMemoryMap(taskAttemptId) == 0) {
          unrollMemoryMap.remove(taskAttemptId) // 如果不用释放内存就直接缓存中逻辑删除
        }
      }
    }
  }

  /**
   * MemoryStore中用于展开Block使用的内存大小.
    * Return the amount of memory currently occupied for unrolling blocks across all tasks.
   */
  def currentUnrollMemory: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.values.sum + offHeapUnrollMemoryMap.values.sum
  }

  /**
   * 当前任务尝试线程用于展开Block所占用的内存.
    * Return the amount of memory currently occupied for unrolling blocks by this task.
   */
  def currentUnrollMemoryForThisTask: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L) +
      offHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L)
  }

  /**
   * 当前使用MemoryStore展开Block的任务数量.
    * Return the number of tasks currently unrolling blocks.
   */
  private def numTasksUnrolling: Int = memoryManager.synchronized {
    (onHeapUnrollMemoryMap.keys ++ offHeapUnrollMemoryMap.keys).toSet.size
  }

  /**
   * Log information about current memory usage.
   */
  private def logMemoryUsage(): Unit = {
    logInfo(
      s"Memory use = ${Utils.bytesToString(blocksMemoryUsed)} (blocks) + " +
      s"${Utils.bytesToString(currentUnrollMemory)} (scratch space shared across " +
      s"$numTasksUnrolling tasks(s)) = ${Utils.bytesToString(memoryUsed)}. " +
      s"Storage limit = ${Utils.bytesToString(maxMemory)}."
    )
  }

  /**
   * Log a warning for failing to unroll a block.
   *
   * @param blockId ID of the block we are trying to unroll.
   * @param finalVectorSize Final size of the vector before unrolling failed.
   */
  private def logUnrollFailureMessage(blockId: BlockId, finalVectorSize: Long): Unit = {
    logWarning(
      s"Not enough space to cache $blockId in memory! " +
      s"(computed ${Utils.bytesToString(finalVectorSize)} so far)"
    )
    logMemoryUsage()
  }
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsValues()]] call.
 *
 * @param memoryStore  the memoryStore, used for freeing memory.
 * @param memoryMode   the memory mode (on- or off-heap).
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param unrolled     an iterator for the partially-unrolled values.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 */
private[storage] class PartiallyUnrolledIterator[T](
    memoryStore: MemoryStore,
    memoryMode: MemoryMode,
    unrollMemory: Long,
    private[this] var unrolled: Iterator[T],
    rest: Iterator[T])
  extends Iterator[T] {

  private def releaseUnrollMemory(): Unit = {
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    // SPARK-17503: Garbage collects the unrolling memory before the life end of
    // PartiallyUnrolledIterator.
    unrolled = null
  }

  override def hasNext: Boolean = {
    if (unrolled == null) {
      rest.hasNext
    } else if (!unrolled.hasNext) {
      releaseUnrollMemory()
      rest.hasNext
    } else {
      true
    }
  }

  override def next(): T = {
    if (unrolled == null || !unrolled.hasNext) {
      rest.next()
    } else {
      unrolled.next()
    }
  }

  /**
   * Called to dispose of this iterator and free its memory.
   */
  def close(): Unit = {
    if (unrolled != null) {
      releaseUnrollMemory()
    }
  }
}

/**
 * A wrapper which allows an open [[OutputStream]] to be redirected to a different sink.
 */
private[storage] class RedirectableOutputStream extends OutputStream {
  private[this] var os: OutputStream = _
  def setOutputStream(s: OutputStream): Unit = { os = s }
  override def write(b: Int): Unit = os.write(b)
  override def write(b: Array[Byte]): Unit = os.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = os.write(b, off, len)
  override def flush(): Unit = os.flush()
  override def close(): Unit = os.close()
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsBytes()]] call.
 *
 * @param memoryStore the MemoryStore, used for freeing memory.
 * @param serializerManager the SerializerManager, used for deserializing values.
 * @param blockId the block id.
 * @param serializationStream a serialization stream which writes to [[redirectableOutputStream]].
 * @param redirectableOutputStream an OutputStream which can be redirected to a different sink.
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param memoryMode whether the unroll memory is on- or off-heap
 * @param bbos byte buffer output stream containing the partially-serialized values.
 *                     [[redirectableOutputStream]] initially points to this output stream.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 * @param classTag the [[ClassTag]] for the block.
 */
private[storage] class PartiallySerializedBlock[T](
    memoryStore: MemoryStore,
    serializerManager: SerializerManager,
    blockId: BlockId,
    private val serializationStream: SerializationStream,
    private val redirectableOutputStream: RedirectableOutputStream,
    val unrollMemory: Long,
    memoryMode: MemoryMode,
    bbos: ChunkedByteBufferOutputStream,
    rest: Iterator[T],
    classTag: ClassTag[T]) {

  private lazy val unrolledBuffer: ChunkedByteBuffer = {
    bbos.close()
    bbos.toChunkedByteBuffer
  }

  // If the task does not fully consume `valuesIterator` or otherwise fails to consume or dispose of
  // this PartiallySerializedBlock then we risk leaking of direct buffers, so we use a task
  // completion listener here in order to ensure that `unrolled.dispose()` is called at least once.
  // The dispose() method is idempotent, so it's safe to call it unconditionally.
  Option(TaskContext.get()).foreach { taskContext =>
    taskContext.addTaskCompletionListener { _ =>
      // When a task completes, its unroll memory will automatically be freed. Thus we do not call
      // releaseUnrollMemoryForThisTask() here because we want to avoid double-freeing.
      unrolledBuffer.dispose()
    }
  }

  // Exposed for testing
  private[storage] def getUnrolledChunkedByteBuffer: ChunkedByteBuffer = unrolledBuffer

  private[this] var discarded = false
  private[this] var consumed = false

  private def verifyNotConsumedAndNotDiscarded(): Unit = {
    if (consumed) {
      throw new IllegalStateException(
        "Can only call one of finishWritingToStream() or valuesIterator() and can only call once.")
    }
    if (discarded) {
      throw new IllegalStateException("Cannot call methods on a discarded PartiallySerializedBlock")
    }
  }

  /**
   * Called to dispose of this block and free its memory.
   */
  def discard(): Unit = {
    if (!discarded) {
      try {
        // We want to close the output stream in order to free any resources associated with the
        // serializer itself (such as Kryo's internal buffers). close() might cause data to be
        // written, so redirect the output stream to discard that data.
        redirectableOutputStream.setOutputStream(ByteStreams.nullOutputStream())
        serializationStream.close()
      } finally {
        discarded = true
        unrolledBuffer.dispose()
        memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
      }
    }
  }

  /**
   * Finish writing this block to the given output stream by first writing the serialized values
   * and then serializing the values from the original input iterator.
   */
  def finishWritingToStream(os: OutputStream): Unit = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    ByteStreams.copy(unrolledBuffer.toInputStream(dispose = true), os)
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    redirectableOutputStream.setOutputStream(os)
    while (rest.hasNext) {
      serializationStream.writeObject(rest.next())(classTag)
    }
    serializationStream.close()
  }

  /**
   * Returns an iterator over the values in this block by first deserializing the serialized
   * values and then consuming the rest of the original input iterator.
   *
   * If the caller does not plan to fully consume the resulting iterator then they must call
   * `close()` on it to free its resources.
   */
  def valuesIterator: PartiallyUnrolledIterator[T] = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // Close the serialization stream so that the serializer's internal buffers are freed and any
    // "end-of-stream" markers can be written out so that `unrolled` is a valid serialized stream.
    serializationStream.close()
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    val unrolledIter = serializerManager.dataDeserializeStream(
      blockId, unrolledBuffer.toInputStream(dispose = true))(classTag)
    // The unroll memory will be freed once `unrolledIter` is fully consumed in
    // PartiallyUnrolledIterator. If the iterator is not consumed by the end of the task then any
    // extra unroll memory will automatically be freed by a `finally` block in `Task`.
    new PartiallyUnrolledIterator(
      memoryStore,
      memoryMode,
      unrollMemory,
      unrolled = unrolledIter,
      rest = rest)
  }
}
