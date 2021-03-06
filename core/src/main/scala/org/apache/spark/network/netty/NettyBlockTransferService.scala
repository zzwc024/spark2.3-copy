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

package org.apache.spark.network.netty
// scalastyle:off
import java.nio.ByteBuffer
import java.util.{HashMap => JHashMap, Map => JMap}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

import com.codahale.metrics.{Metric, MetricSet}

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.network._
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.network.client.{RpcResponseCallback, TransportClientBootstrap, TransportClientFactory}
import org.apache.spark.network.crypto.{AuthClientBootstrap, AuthServerBootstrap}
import org.apache.spark.network.server._
import org.apache.spark.network.shuffle.{BlockFetchingListener, OneForOneBlockFetcher, RetryingBlockFetcher, TempFileManager}
import org.apache.spark.network.shuffle.protocol.UploadBlock
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.storage.{BlockId, StorageLevel}
import org.apache.spark.util.Utils

/**
 * A BlockTransferService that uses Netty to fetch a set of blocks at time.
 */
private[spark] class NettyBlockTransferService(
    conf: SparkConf,
    securityManager: SecurityManager,
    bindAddress: String,
    override val hostName: String,
    _port: Int,
    numCores: Int)
  extends BlockTransferService {

  // TODO: Don't use Java serialization, use a more cross-version compatible serialization format.
  private val serializer = new JavaSerializer(conf)
  private val authEnabled = securityManager.isAuthenticationEnabled()
  private val transportConf = SparkTransportConf.fromSparkConf(conf, "shuffle", numCores)

  private[this] var transportContext: TransportContext = _
  private[this] var server: TransportServer = _
  private[this] var clientFactory: TransportClientFactory = _
  private[this] var appId: String = _

  override def init(blockDataManager: BlockDataManager): Unit = {
    // 创建NettyBlockRpcServer.其继承了RPCHandler,Block读写请求都会经由创建NettyBlockRpcServer.
    val rpcHandler = new NettyBlockRpcServer(conf.getAppId, serializer, blockDataManager)
    // 创建客户端服务端的引导对象
    var serverBootstrap: Option[TransportServerBootstrap] = None
    var clientBootstrap: Option[TransportClientBootstrap] = None
    if (authEnabled) {
      serverBootstrap = Some(new AuthServerBootstrap(transportConf, securityManager))
      clientBootstrap = Some(new AuthClientBootstrap(transportConf, conf.getAppId, securityManager))
    }
    // 创建TransportContext
    transportContext = new TransportContext(transportConf, rpcHandler)
    // 客户端工厂
    clientFactory = transportContext.createClientFactory(clientBootstrap.toSeq.asJava)
    // 创建transportServer
    server = createServer(serverBootstrap.toList)
    // 应用id
    appId = conf.getAppId
    logInfo(s"Server created on ${hostName}:${server.getPort}")
  }

  /** Creates and binds the TransportServer, possibly trying multiple ports. */
  private def createServer(bootstraps: List[TransportServerBootstrap]): TransportServer = {
    def startService(port: Int): (TransportServer, Int) = {
      val server = transportContext.createServer(bindAddress, port, bootstraps.asJava)
      (server, server.getPort)
    }

    Utils.startServiceOnPort(_port, startService, conf, getClass.getName)._1
  }

  override def shuffleMetrics(): MetricSet = {
    require(server != null && clientFactory != null, "NettyBlockTransferServer is not initialized")

    new MetricSet {
      val allMetrics = new JHashMap[String, Metric]()
      override def getMetrics: JMap[String, Metric] = {
        allMetrics.putAll(clientFactory.getAllMetrics.getMetrics)
        allMetrics.putAll(server.getAllMetrics.getMetrics)
        allMetrics
      }
    }
  }

  override def fetchBlocks(
      host: String,
      port: Int,
      execId: String,
      blockIds: Array[String],
      listener: BlockFetchingListener,
      tempFileManager: TempFileManager): Unit = {
    logTrace(s"Fetch blocks from $host:$port (executor id $execId)")
    try {
      // 匿名类实现createAndStart
      val blockFetchStarter = new RetryingBlockFetcher.BlockFetchStarter {
        override def createAndStart(blockIds: Array[String], listener: BlockFetchingListener) {
          // 创建客户端
          val client = clientFactory.createClient(host, port)
          // 创建一个一对一的BlockFetcher
          new OneForOneBlockFetcher(client, appId, execId, blockIds, listener,
            transportConf, tempFileManager).start()
        }
      }
      // 获取最大尝试次数
      val maxRetries = transportConf.maxIORetries()
      if (maxRetries > 0) {
        // 最大尝试次数大于0,创建RetryingBlockFetcher,并调用start方法,
        // 其中的blockFetchStarter就是创建的局部变量.
        // Note this Fetcher will correctly handle maxRetries == 0; we avoid it just in case there's
        // a bug in this code. We should remove the if statement once we're sure of the stability.
        new RetryingBlockFetcher(transportConf, blockFetchStarter, blockIds, listener).start()
      } else {
        // 不>0直接调用blockFetchStarter.createAndStart
        blockFetchStarter.createAndStart(blockIds, listener)
      }
    } catch {
      case e: Exception =>
        logError("Exception while beginning fetchBlocks", e)
        blockIds.foreach(listener.onBlockFetchFailure(_, e))
    }
  }

  override def port: Int = server.getPort
  // 向远端上传block
  override def uploadBlock(
      hostname: String,
      port: Int,
      execId: String,
      blockId: BlockId,
      blockData: ManagedBuffer,
      level: StorageLevel,
      classTag: ClassTag[_]): Future[Unit] = {
    // 创建空Promise,调用方法持有该Promise的futrue.
    val result = Promise[Unit]()
    // 创建rpc客户端
    val client = clientFactory.createClient(hostname, port)
    // 序列化存储级别和类型标记
    // StorageLevel and ClassTag are serialized as bytes using our JavaSerializer.
    // Everything else is encoded using our binary protocol.
    val metadata = JavaUtils.bufferToArray(serializer.newInstance().serialize((level, classTag)))
    // 将Block数据转换或复制为Nio的ByteBuffer类型
    // Convert or copy nio buffer into array in order to serialize it.
    val array = JavaUtils.bufferToArray(blockData.nioByteBuffer())
    // 发送Rpc消息UploadBlock,
    client.sendRpc(new UploadBlock(appId, execId, blockId.name, metadata, array).toByteBuffer,
      new RpcResponseCallback {
        override def onSuccess(response: ByteBuffer): Unit = {
          logTrace(s"Successfully uploaded block $blockId")
          // 成功的话调用Promise的success方法
          result.success((): Unit)
        }
        override def onFailure(e: Throwable): Unit = {
          logError(s"Error while uploading block $blockId", e)
          // 失败调用failure方法
          result.failure(e)
        }
      })
    // 返回引用
    result.future
  }

  override def close(): Unit = {
    if (server != null) {
      server.close()
    }
    if (clientFactory != null) {
      clientFactory.close()
    }
  }
}
