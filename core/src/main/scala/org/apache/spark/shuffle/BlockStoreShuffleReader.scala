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
import org.apache.spark._
import org.apache.spark.internal.{config, Logging}
import org.apache.spark.serializer.SerializerManager
import org.apache.spark.storage.{BlockManager, ShuffleBlockFetcherIterator}
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.collection.ExternalSorter

/**
 * 用于Shuffle执行过程中,reduce任务从其他节点的Block文件中读取[startPartition, endPartition)范围内的
  * 数据.
  * Fetches and reads the partitions in range [startPartition, endPartition) from a shuffle by
 * requesting them from other nodes' block stores.
 */
private[spark] class BlockStoreShuffleReader[K, C](
    handle: BaseShuffleHandle[K, _, C],
    startPartition: Int, // 分区起点
    endPartition: Int,   // 分区终点
    context: TaskContext,// task上下文
    serializerManager: SerializerManager = SparkEnv.get.serializerManager,// 序列化管理
    blockManager: BlockManager = SparkEnv.get.blockManager, // block管理
    mapOutputTracker: MapOutputTracker = SparkEnv.get.mapOutputTracker)
  extends ShuffleReader[K, C] with Logging {
  /** handle的shuffle依赖*/
  private val dep = handle.dependency

  /**
    * 为本reduceTask读取绑定的键值对
    * Read the combined key-values for this reduce task */
  override def read(): Iterator[Product2[K, C]] = {
    // 创建ShuffleBlockFetcherIterator.ShuffleBlockFetcherIterator初始化时
    // 会划分本地和远程的block,并且获取了本地和远端的Block,将获取的block封装进
    // SuccessFetchResult或者FailureFetchResult放入results队列
    val wrappedStreams = new ShuffleBlockFetcherIterator(
      context,
      blockManager.shuffleClient,
      blockManager,
      mapOutputTracker.getMapSizesByExecutorId(handle.shuffleId, startPartition, endPartition),
      serializerManager.wrapStream,
      // Note: we use getSizeAsMb when no suffix is provided for backwards compatibility
      SparkEnv.get.conf.getSizeAsMb("spark.reducer.maxSizeInFlight", "48m") * 1024 * 1024,
      SparkEnv.get.conf.getInt("spark.reducer.maxReqsInFlight", Int.MaxValue),
      SparkEnv.get.conf.get(config.REDUCER_MAX_BLOCKS_IN_FLIGHT_PER_ADDRESS),
      SparkEnv.get.conf.get(config.MAX_REMOTE_BLOCK_SIZE_FETCH_TO_MEM),
      SparkEnv.get.conf.getBoolean("spark.shuffle.detectCorrupt", true))
    // 获取序列化实例
    val serializerInstance = dep.serializer.newInstance()

    // Create a key/value iterator for each stream
    // 为每个流创建键值对迭代器,
    val recordIter = wrappedStreams.flatMap { case (blockId, wrappedStream) =>
      // 注意：下面的asKeyValueIterator将一个键/值迭代器包装在NextIterator中。
      // NextIterator确保在读取所有记录时在底层InputStream上调用close（）。
      // Note: the asKeyValueIterator below wraps a key/value iterator inside of a
      // NextIterator. The NextIterator makes sure that close() is called on the
      // underlying InputStream when all records have been read.
      serializerInstance.deserializeStream(wrappedStream).asKeyValueIterator
    }

    // Update the context task metrics for each record read.
    // 为每个读取的记录更新taskMetrics
    val readMetrics = context.taskMetrics.createTempShuffleReadMetrics()
    val metricIter = CompletionIterator[(Any, Any), Iterator[(Any, Any)]](
      recordIter.map { record =>
        readMetrics.incRecordsRead(1)
        record
      },
      context.taskMetrics().mergeShuffleReadMetrics())

    // An interruptible iterator must be used here in order to support task cancellation
    // 这里使用interruptibleIter
    val interruptibleIter = new InterruptibleIterator[(Any, Any)](context, metricIter)
    // 如果指定了聚合函数
    val aggregatedIter: Iterator[Product2[K, C]] = if (dep.aggregator.isDefined) {

      if (dep.mapSideCombine) {
        // 并且允许在map端进行合并,在reduce端对数据进行合并
        // We are reading values that are already combined
        val combinedKeyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, C)]]
        dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
      } else {
        // 如果不允许在map端进行合并,那就在reduce端对数据进行缓存
        // We don't know the value type, but also don't care -- the dependency *should*
        // have made sure its compatible w/ this aggregator, which will convert the value
        // type to the combined type C
        val keyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]]
        dep.aggregator.get.combineValuesByKey(keyValuesIterator, context)
      }
    } else {
      // 没有指定聚合函数,不做任何处理
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      interruptibleIter.asInstanceOf[Iterator[Product2[K, C]]]
    }

    // Sort the output if there is a sort ordering defined.
    // 如果指定了排序函数,将输出排序
    dep.keyOrdering match {
      case Some(keyOrd: Ordering[K]) =>
        // Create an ExternalSorter to sort the data.
        // 创建ExeternalSorter对数据排序
        val sorter =
          new ExternalSorter[K, C, C](context, ordering = Some(keyOrd), serializer = dep.serializer)
        // 对数据进行缓存
        sorter.insertAll(aggregatedIter)
        context.taskMetrics().incMemoryBytesSpilled(sorter.memoryBytesSpilled)
        context.taskMetrics().incDiskBytesSpilled(sorter.diskBytesSpilled)
        context.taskMetrics().incPeakExecutionMemory(sorter.peakMemoryUsedBytes)
        CompletionIterator[Product2[K, C], Iterator[Product2[K, C]]](sorter.iterator, sorter.stop())
      case None =>
        // 如果没有指定排序函数,返回aggregatedIter
        aggregatedIter
    }
  }
}
