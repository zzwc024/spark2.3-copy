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

package org.apache.spark.shuffle
// scalastyle:off
import java.io._
import java.nio.channels.Channels
import java.nio.file.Files

import org.apache.spark.{SparkConf, SparkEnv}
import org.apache.spark.internal.Logging
import org.apache.spark.io.NioBufferedFileInputStream
import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer}
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.shuffle.IndexShuffleBlockResolver.NOOP_REDUCE_ID
import org.apache.spark.storage._
import org.apache.spark.util.Utils

/**
  * 创建和维护shuffle块在逻辑块和物理文件位置之间的映射。 来自相同map任务的ShuffleBlock数据存储在
  * 单个合并数据文件中。数据文件中数据block的偏移量存储在单独的索引文件中。
  *  我们使用shuffle数据的名称shuffleBlockId，其中reduce ID设置为0，并将“.data”添加为数据文件的文件名后缀
  * ，并将“.index”添加为索引文件的文件名后缀。
  * Create and maintain the shuffle blocks' mapping between logic block and physical file location.
 * Data of shuffle blocks from the same map task are stored in a single consolidated data file.
 * The offsets of the data blocks in the data file are stored in a separate index file.
 *
 * We use the name of the shuffle data's shuffleBlockId with reduce ID set to 0 and add ".data"
 * as the filename postfix for data file, and ".index" as the filename postfix for index file.
 *
 */
// Note: Changes to the format in this file should be kept in sync with
// org.apache.spark.network.shuffle.ExternalShuffleBlockResolver#getSortBasedShuffleBlockData().
private[spark] class IndexShuffleBlockResolver(
    conf: SparkConf,
    _blockManager: BlockManager = null)
  extends ShuffleBlockResolver
  with Logging {
  // SparkEnv中的组件BlockManager
  private lazy val blockManager = Option(_blockManager).getOrElse(SparkEnv.get.blockManager)
  // shuffle相关的TransportConf
  private val transportConf = SparkTransportConf.fromSparkConf(conf, "shuffle")
  /**
    * 用于获取Shuffle数据文件,实际是blockManager.diskBlockManager.getFile
    * */
  def getDataFile(shuffleId: Int, mapId: Int): File = {
    blockManager.diskBlockManager.getFile(ShuffleDataBlockId(shuffleId, mapId, NOOP_REDUCE_ID))
  }
  /**
    * 用于获取Shuffle索引文件.获取指定shuffle的指定map输出的索引文件.
    * */
  private def getIndexFile(shuffleId: Int, mapId: Int): File = {
    blockManager.diskBlockManager.getFile(ShuffleIndexBlockId(shuffleId, mapId, NOOP_REDUCE_ID))
  }

  /**
   * 删除Shuffle过程中包含指定map任务输出数据的shuffle数据文件和索引文件
    * Remove data file and index file that contain the output data from one map.
   */
  def removeDataByMap(shuffleId: Int, mapId: Int): Unit = {
    var file = getDataFile(shuffleId, mapId)
    if (file.exists()) {
      if (!file.delete()) {// 删除数据文件
        logWarning(s"Error deleting data ${file.getPath()}")
      }
    }
    // 获取指定Shuffle中指定map任务输出的索引文件
    file = getIndexFile(shuffleId, mapId)
    if (file.exists()) {
      if (!file.delete()) { // 删除索引文件
        logWarning(s"Error deleting index ${file.getPath()}")
      }
    }
  }

  /**
   * 检查给定的索引和数据文件是否匹配,如果是,返回数据文件的分区长度数组,否则返回null.<br>
    * Check whether the given index and data files match each other.
   * If so, return the partition lengths in the data file. Otherwise return null.
   */
  private def checkIndexAndDataFile(index: File, data: File, blocks: Int): Array[Long] = {
    // the index file should have `block + 1` longs as offset.
    if (index.length() != (blocks + 1) * 8L) {
      return null
    }
    val lengths = new Array[Long](blocks)
    // Read the lengths of blocks
    val in = try {
      new DataInputStream(new NioBufferedFileInputStream(index))
    } catch {
      case e: IOException =>
        return null
    }
    try {
      // Convert the offsets into lengths of each block
      var offset = in.readLong()
      if (offset != 0L) {
        return null
      }
      var i = 0
      while (i < blocks) {
        val off = in.readLong()
        lengths(i) = off - offset
        offset = off
        i += 1
      }
    } catch {
      case e: IOException =>
        return null
    } finally {
      in.close()
    }

    // the size of data file should match with index file
    if (data.length() == lengths.sum) {
      lengths
    } else {
      null
    }
  }

  /**
   * 将每个Block的偏移量写入索引文件,并在最后增加一个表示输出文件末尾的偏移量
    * Write an index file with the offsets of each block, plus a final offset at the end for the
   * end of the output file. This will be used by getBlockData to figure out where each block
   * begins and ends.
   *
   * It will commit the data and index file as an atomic operation, use the existing ones, or
   * replace them with new ones.
   *
   * Note: the `lengths` will be updated to match the existing index file if use the existing ones.
   */
  def writeIndexFileAndCommit(
      shuffleId: Int,
      mapId: Int,
      lengths: Array[Long],
      dataTmp: File): Unit = {
    // 获取指定shuffle中指定map任务输出的索引文件
    val indexFile = getIndexFile(shuffleId, mapId)
    // 获取索引文件路径
    val indexTmp = Utils.tempFileWith(indexFile)
    try {
      val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(indexTmp)))
      Utils.tryWithSafeFinally {
        // We take in lengths of each block, need to convert it to offsets.
        var offset = 0L
        out.writeLong(offset)
        // 遍历每个Block长度,并作为偏移量写入临时索引文件
        for (length <- lengths) {
          offset += length
          out.writeLong(offset)
        }
      } {
        out.close()
      }
      // 获取map输出索引文件
      val dataFile = getDataFile(shuffleId, mapId)
      // There is only one IndexShuffleBlockResolver per executor, this synchronization make sure
      // the following check and rename are atomic.
      synchronized {
        // 检查是索引文件和数据文件是否匹配,checkIndexAndDataFile返回各个partition的长度数组
        val existingLengths = checkIndexAndDataFile(indexFile, dataFile, lengths.length)
        if (existingLengths != null) {
          // 索引文件和数据文件不匹配,将临时索引文件和临时数据文件删除
          // Another attempt for the same task has already written our map outputs successfully,
          // so just use the existing partition lengths and delete our temporary map outputs.
          // 把existingLengths复制到参数lengths数组上
          System.arraycopy(existingLengths, 0, lengths, 0, lengths.length)
          if (dataTmp != null && dataTmp.exists()) {
            dataTmp.delete()
          }
          indexTmp.delete()
        } else {
          // 索引文件和数据文件匹配,将临时索引文件和数据文件作为正式的索引和数据文件
          // This is the first successful attempt in writing the map outputs for this task,
          // so override any existing index and data files with the ones we wrote.
          // 说明是map任务中间结果输出的第一次成功尝试,需要将indexTmp和dataTmp重命名
          if (indexFile.exists()) {
            indexFile.delete()
          }
          if (dataFile.exists()) {
            dataFile.delete()
          }
          if (!indexTmp.renameTo(indexFile)) {
            throw new IOException("fail to rename file " + indexTmp + " to " + indexFile)
          }
          if (dataTmp != null && dataTmp.exists() && !dataTmp.renameTo(dataFile)) {
            throw new IOException("fail to rename file " + dataTmp + " to " + dataFile)
          }
        }
      }
    } finally {
      if (indexTmp.exists() && !indexTmp.delete()) {
        logError(s"Failed to delete temporary index file at ${indexTmp.getAbsolutePath}")
      }
    }
  }
  /** 获取指定的ShuffleBlockId对应的数据*/
  override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
    // The block is actually going to be a range of a single map output file for this map, so
    // find out the consolidated file, then the offset within that from our index
    // 获取指定map任务输出的索引文件
    val indexFile = getIndexFile(blockId.shuffleId, blockId.mapId)
    // 谷歌翻译:SPARK-22982：如果此FileInputStream的位置被另一段错误使用我们的文件描述符的代码向前搜索，
    // 则此代码将获取错误的偏移量（这可能导致reducer被发送不同的reducer数据）。
    // 此处添加的显式位置检查在SPARK-22982期间是一个有用的调试辅助工具，
    // 可能有助于防止此类问题在将来再次发生，这就是为什么即使修复了SPARK-22982也将它们留在这里的原因。
    // SPARK-22982: if this FileInputStream's position is seeked forward by another piece of code
    // which is incorrectly using our file descriptor then this code will fetch the wrong offsets
    // (which may cause a reducer to be sent a different reducer's data). The explicit position
    // checks added here were a useful debugging aid during SPARK-22982 and may help prevent this
    // class of issue from re-occurring in the future which is why they are left here even though
    // SPARK-22982 is fixed.
    // 获取文件channel,下面是一段nio的操作,看不太明白可以了解一下nio的buffer和channel
    val channel = Files.newByteChannel(indexFile.toPath)
    // 设置channel读取的位置
    channel.position(blockId.reduceId * 8L)
    // 获取数据输入流
    val in = new DataInputStream(Channels.newInputStream(channel))
    try {
      // 读取偏移量
      val offset = in.readLong()
      // 读取下一个偏移量
      val nextOffset = in.readLong()
      //  实际的位置
      val actualPosition = channel.position()
      // 期待的位置
      val expectedPosition = blockId.reduceId * 8L + 16
      if (actualPosition != expectedPosition) {
        throw new Exception(s"SPARK-22982: Incorrect channel position after index file reads: " +
          s"expected $expectedPosition but actual position was $actualPosition.")
      }
      // 创建并返回FileSegmentManagedBuffer
      new FileSegmentManagedBuffer(
        transportConf,
        getDataFile(blockId.shuffleId, blockId.mapId),
        offset,
        nextOffset - offset)
    } finally {
      in.close()
    }
  }

  override def stop(): Unit = {}
}

private[spark] object IndexShuffleBlockResolver {
  // No-op reduce ID used in interactions with disk store.
  // The disk store currently expects puts to relate to a (map, reduce) pair, but in the sort
  // shuffle outputs for several reduces are glommed into a single file.
  val NOOP_REDUCE_ID = 0
}
