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

package org.apache.spark
// scalastyle:off
import java.io.File
import java.net.Socket
import java.util.Locale

import scala.collection.mutable
import scala.util.Properties

import com.google.common.collect.MapMaker

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.api.python.PythonWorkerFactory
import org.apache.spark.broadcast.BroadcastManager
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.memory.{MemoryManager, StaticMemoryManager, UnifiedMemoryManager}
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.network.netty.NettyBlockTransferService
import org.apache.spark.rpc.{RpcEndpoint, RpcEndpointRef, RpcEnv}
import org.apache.spark.scheduler.{LiveListenerBus, OutputCommitCoordinator}
import org.apache.spark.scheduler.OutputCommitCoordinator.OutputCommitCoordinatorEndpoint
import org.apache.spark.security.CryptoStreamUtils
import org.apache.spark.serializer.{JavaSerializer, Serializer, SerializerManager}
import org.apache.spark.shuffle.ShuffleManager
import org.apache.spark.storage._
import org.apache.spark.util.{RpcUtils, Utils}

/**
 * spark运行时环境.包括serializer, RpcEnv, block manager, map output tracker等等.
  * Spark的用户代码通过全局变量找到SaprkEnv,所以多线程间共享相同的SparkEnv.可以通过SparkEnv.get来获取SparkEnv
  * :: DeveloperApi ::
 * Holds all the runtime environment objects for a running Spark instance (either master or worker),
 * including the serializer, RpcEnv, block manager, map output tracker, etc. Currently
 * Spark code finds the SparkEnv through a global variable, so all the threads can access the same
 * SparkEnv. It can be accessed by SparkEnv.get (e.g. after creating a SparkContext).
 *
 * NOTE: This is not intended for external use. This is exposed for Shark and may be made private
 *       in a future release.
 */
@DeveloperApi
class SparkEnv (
    val executorId: String,
    private[spark] val rpcEnv: RpcEnv,
    val serializer: Serializer,
    val closureSerializer: Serializer,
    val serializerManager: SerializerManager,
    val mapOutputTracker: MapOutputTracker,
    val shuffleManager: ShuffleManager,
    val broadcastManager: BroadcastManager,
    val blockManager: BlockManager,
    val securityManager: SecurityManager,
    val metricsSystem: MetricsSystem,
    val memoryManager: MemoryManager,
    val outputCommitCoordinator: OutputCommitCoordinator,
    val conf: SparkConf) extends Logging {
  // 当前SparkEnv是否停止
  private[spark] var isStopped = false
  // 所有Python实现的Worker的缓存
  private val pythonWorkers = mutable.HashMap[(String, Map[String, String]), PythonWorkerFactory]()
  // HadooRDD进行任务切分时所需要的元数据软引用.如,HadoopFileRDD用其缓存JobConfs和InputFormats
  // A general, soft-reference map for metadata needed during HadoopRDD split computation
  // (e.g., HadoopFileRDD uses this to cache JobConfs and InputFormats).
  private[spark] val hadoopJobMetadata = new MapMaker().softValues().makeMap[String, Any]()
  // 如果当前SparkEnv处于Dirver中,那么创建Driver的临时目录
  private[spark] var driverTmpDir: Option[String] = None

  private[spark] def stop() {

    if (!isStopped) {
      isStopped = true
      pythonWorkers.values.foreach(_.stop())
      mapOutputTracker.stop()
      shuffleManager.stop()
      broadcastManager.stop()
      blockManager.stop()
      blockManager.master.stop()
      metricsSystem.stop()
      outputCommitCoordinator.stop()
      rpcEnv.shutdown()
      rpcEnv.awaitTermination()

      // If we only stop sc, but the driver process still run as a services then we need to delete
      // the tmp dir, if not, it will create too many tmp dirs.
      // We only need to delete the tmp dir create by driver
      driverTmpDir match {
        case Some(path) =>
          try {
            Utils.deleteRecursively(new File(path))
          } catch {
            case e: Exception =>
              logWarning(s"Exception while deleting Spark temp dir: $path", e)
          }
        case None => // We just need to delete tmp dir created by driver, so do nothing on executor
      }
    }
  }

  private[spark]
  def createPythonWorker(pythonExec: String, envVars: Map[String, String]): java.net.Socket = {
    synchronized {
      val key = (pythonExec, envVars)
      pythonWorkers.getOrElseUpdate(key, new PythonWorkerFactory(pythonExec, envVars)).create()
    }
  }

  private[spark]
  def destroyPythonWorker(pythonExec: String, envVars: Map[String, String], worker: Socket) {
    synchronized {
      val key = (pythonExec, envVars)
      pythonWorkers.get(key).foreach(_.stopWorker(worker))
    }
  }

  private[spark]
  def releasePythonWorker(pythonExec: String, envVars: Map[String, String], worker: Socket) {
    synchronized {
      val key = (pythonExec, envVars)
      pythonWorkers.get(key).foreach(_.releaseWorker(worker))
    }
  }
}

object SparkEnv extends Logging {
  @volatile private var env: SparkEnv = _

  private[spark] val driverSystemName = "sparkDriver"
  private[spark] val executorSystemName = "sparkExecutor"

  def set(e: SparkEnv) {
    env = e
  }

  /**
   * Returns the SparkEnv.
   */
  def get: SparkEnv = {
    env
  }

  /**
   * 创建驱动程序的saprkEnv,几个参数
    * @param conf  SparkConf对象,内含配置信息
    * @param isLocal 是否本地
    * @param listenerBus 使用的消息总线
    * @param numCores 核心数
    * @param mockOutputCommitCoordinator  模拟输出提交协调器
    * Create a SparkEnv for the driver.
   */
  private[spark] def createDriverEnv(
      conf: SparkConf,
      isLocal: Boolean,
      listenerBus: LiveListenerBus,
      numCores: Int,
      mockOutputCommitCoordinator: Option[OutputCommitCoordinator] = None): SparkEnv = {
    assert(conf.contains(DRIVER_HOST_ADDRESS),//如果不包含DRIVER_HOST_ADDRESS配置
      s"${DRIVER_HOST_ADDRESS.key} is not set on the driver!")
    assert(conf.contains("spark.driver.port"), "spark.driver.port is not set on the driver!")
    val bindAddress = conf.get(DRIVER_BIND_ADDRESS)
    val advertiseAddress = conf.get(DRIVER_HOST_ADDRESS)
    val port = conf.get("spark.driver.port").toInt
    val ioEncryptionKey = if (conf.get(IO_ENCRYPTION_ENABLED)) {
      Some(CryptoStreamUtils.createKey(conf))
    } else {
      None
    }
    //上面几个参数都是下面这个方法需要的参数
    create(
      conf,
      SparkContext.DRIVER_IDENTIFIER,
      bindAddress,
      advertiseAddress,
      Option(port),
      isLocal,
      numCores,
      ioEncryptionKey,
      listenerBus = listenerBus,
      mockOutputCommitCoordinator = mockOutputCommitCoordinator
    )
  }

  /**
   * Create a SparkEnv for an executor.
   * In coarse-grained mode, the executor provides an RpcEnv that is already instantiated.
   */
  private[spark] def createExecutorEnv(
      conf: SparkConf,
      executorId: String,
      hostname: String,
      numCores: Int,
      ioEncryptionKey: Option[Array[Byte]],
      isLocal: Boolean): SparkEnv = {
    val env = create(
      conf,
      executorId,
      hostname,
      hostname,
      None,
      isLocal,
      numCores,
      ioEncryptionKey
    )
    SparkEnv.set(env)
    env
  }

  /**
   * 这个方法来创建SparkEnv 很多参数
    * Helper method to create a SparkEnv for a driver or an executor.
   */
  private def create(
      conf: SparkConf, //spark配置
      executorId: String, //executor的id
      bindAddress: String, //绑定地址
      advertiseAddress: String, //广播地址
      port: Option[Int], //端口号
      isLocal: Boolean, //是否本地模式
      numUsableCores: Int, //使用核心数
      ioEncryptionKey: Option[Array[Byte]], //io秘钥
      listenerBus: LiveListenerBus = null,  //时间总线,可以当做邮局.
      mockOutputCommitCoordinator: Option[OutputCommitCoordinator] = None): SparkEnv = {

    val isDriver = executorId == SparkContext.DRIVER_IDENTIFIER  //该executorId是否与DriverId相等

    // Listener bus is only used on the driver
    if (isDriver) { //如果是driver的话  listenerBus不能为空.
      assert(listenerBus != null, "Attempted to create driver SparkEnv with null listener bus!")
    }
    //如果是driver 需要验证安全
    val securityManager = new SecurityManager(conf, ioEncryptionKey)
    if (isDriver) {
      securityManager.initializeAuth()
    }
    //对于io秘钥,遍历并且如果!securityManager.isEncryptionEnabled()就记录警告日志.
    ioEncryptionKey.foreach { _ =>
      if (!securityManager.isEncryptionEnabled()) {
        logWarning("I/O encryption enabled without RPC encryption: keys will be visible on the " +
          "wire.")
      }
    }
    //设置名称
    val systemName = if (isDriver) driverSystemName else executorSystemName
    // 创建RpcEnv 名称,地址,端口号等等.....
    val rpcEnv = RpcEnv.create(systemName, bindAddress, advertiseAddress, port.getOrElse(-1), conf,
      securityManager, numUsableCores, !isDriver)

    // Figure out which port RpcEnv actually bound to in case the original port is 0 or occupied.
    if (isDriver) {
      conf.set("spark.driver.port", rpcEnv.address.port.toString) //如果是driver,为conf添加端口号
    }

    // Create an instance of the class with the given name, possibly initializing it with our conf
    // 通过类名创建类
    def instantiateClass[T](className: String): T = {
      val cls = Utils.classForName(className)
      // Look for a constructor taking a SparkConf and a boolean isDriver, then one taking just
      // SparkConf, then one taking no arguments
      try {
        cls.getConstructor(classOf[SparkConf], java.lang.Boolean.TYPE)
          .newInstance(conf, new java.lang.Boolean(isDriver))
          .asInstanceOf[T]
      } catch {
        case _: NoSuchMethodException =>
          try {
            cls.getConstructor(classOf[SparkConf]).newInstance(conf).asInstanceOf[T]
          } catch {
            case _: NoSuchMethodException =>
              cls.getConstructor().newInstance().asInstanceOf[T]
          }
      }
    }
    // 又定义了一个方法,通过配置文件名获取类名
    // Create an instance of the class named by the given SparkConf property, or defaultClassName
    // if the property is not set, possibly initializing it with our conf
    def instantiateClassFromConf[T](propertyName: String, defaultClassName: String): T = {
      instantiateClass[T](conf.get(propertyName, defaultClassName))
    }

    // 通过上面的方法获取序列化对象,可通过配置指定spark.serializer
    val serializer = instantiateClassFromConf[Serializer](
      "spark.serializer", "org.apache.spark.serializer.JavaSerializer")
    logDebug(s"Using serializer: ${serializer.getClass}")
    // 创建序列化管理对象
    val serializerManager = new SerializerManager(serializer, conf, ioEncryptionKey)
    // closureSerializer类型固定为JavaSerializer,不能通过配置指定
    val closureSerializer = new JavaSerializer(conf)
    //注册或查找endPoint
    def registerOrLookupEndpoint(
        name: String, endpointCreator: => RpcEndpoint):
      RpcEndpointRef = {
      if (isDriver) {//是driver的话注册
        logInfo("Registering " + name)
        rpcEnv.setupEndpoint(name, endpointCreator)
      } else {
        RpcUtils.makeDriverRef(name, conf, rpcEnv)//不是driver查找
      }
    }
    //广播管理
    val broadcastManager = new BroadcastManager(isDriver, conf, securityManager)
    //创建map阶段任务的输出追踪
    val mapOutputTracker = if (isDriver) {
      new MapOutputTrackerMaster(conf, broadcastManager, isLocal)//是driver创建master
    } else {
      new MapOutputTrackerWorker(conf) // 不是driver创建Worker
    }
    // 必须在初始化之后将mapOutputTracker的trackerEndPoint指定为MapOutputTrackerEndpoint
    // Have to assign trackerEndpoint after initialization as MapOutputTrackerEndpoint
    // requires the MapOutputTracker itself
    mapOutputTracker.trackerEndpoint = registerOrLookupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(
        rpcEnv, mapOutputTracker.asInstanceOf[MapOutputTrackerMaster], conf))

    // Let the user specify short names for shuffle managers
    // 为shuffleManager指定名称,生成一个名称对应类全称的map
    val shortShuffleMgrNames = Map(
      "sort" -> classOf[org.apache.spark.shuffle.sort.SortShuffleManager].getName,
      "tungsten-sort" -> classOf[org.apache.spark.shuffle.sort.SortShuffleManager].getName)
    // spark.shuffle.manager中获取名称,默认是sort
    val shuffleMgrName = conf.get("spark.shuffle.manager", "sort")
    // 默认使用shuffleMgrName名称的类名
    val shuffleMgrClass =
      shortShuffleMgrNames.getOrElse(shuffleMgrName.toLowerCase(Locale.ROOT), shuffleMgrName)
    val shuffleManager = instantiateClass[ShuffleManager](shuffleMgrClass)//创建shuffleManager

    val useLegacyMemoryManager = conf.getBoolean("spark.memory.useLegacyMode", false)
    // 创建内存管理
    val memoryManager: MemoryManager =
      if (useLegacyMemoryManager) {//配置文件有的话就可以创建
        // 老版本spark遗留的内存管理
        new StaticMemoryManager(conf, numUsableCores)
      } else {
        //useLegacyMemoryManager等于false,默认是false.创建UnifiedMemoryManager
        UnifiedMemoryManager(conf, numUsableCores)
      }

    val blockManagerPort = if (isDriver) {
      //设置Block管理的端口,Block是Spark处理数据的最小单元.如果是driver,就以DRIVER_BLOCK_MANAGER_PORT作为端口号
      conf.get(DRIVER_BLOCK_MANAGER_PORT)
    } else {
      conf.get(BLOCK_MANAGER_PORT) // executor以BLOCK_MANAGER_PORT作为端口号
    }
    // 创建Block传输的服务,这使用的基于Netty的传输服务
    val blockTransferService =
    // mapoutputTracker和 NettyBlockTransferService配合形成了shuffle这个功能
      new NettyBlockTransferService(conf, securityManager, bindAddress, advertiseAddress,
        blockManagerPort, numUsableCores)
    // Block管理的主节点
    val blockManagerMaster = new BlockManagerMaster(registerOrLookupEndpoint(
      BlockManagerMaster.DRIVER_ENDPOINT_NAME,
      new BlockManagerMasterEndpoint(rpcEnv, isLocal, conf, listenerBus)),
      conf, isDriver)

    // NB: blockManager is not valid until initialize() is called later.
    // 在initialize调用之后blockManager才有效
    val blockManager = new BlockManager(executorId, rpcEnv, blockManagerMaster,
      serializerManager, conf, memoryManager, mapOutputTracker, shuffleManager,
      blockTransferService, securityManager, numUsableCores)

    val metricsSystem = if (isDriver) {
      // metrics是性能监测系统,如果是Driver的话暂时不启用监测系统,我们需要启动task的调度系统分配appID之后再启动监测系统
      // Don't start metrics system right now for Driver.
      // We need to wait for the task scheduler to give us an app ID.
      // Then we can start the metrics system.
      MetricsSystem.createMetricsSystem("driver", conf, securityManager)
    } else {
      // 我们需要在创建MetricsSystem之前设置执行程序标识，
      // 因为在度量标准配置文件中指定的源代码和接收程序将希望将此执行程序的标识纳入其报告的指标中.
      // We need to set the executor ID before the MetricsSystem is created because sources and
      // sinks specified in the metrics configuration file will want to incorporate this executor's
      // ID into the metrics they report.
      conf.set("spark.executor.id", executorId)
      val ms = MetricsSystem.createMetricsSystem("executor", conf, securityManager)
      ms.start()
      ms
    }
    // 创建输出提交的协调器和引用就可以创建SparkEnv的实例了
    // 如果当前实例是driver,创建OutputCommitCoordinatorEndpoint,并且注册到Dispatcher中
    // 注册名为OutputCommitCoordinator.如果是executor,则从Driver
    // 实例的NettyRpcEnv的Dispatcher中查找OutputCommitCoordinatorEndpoint的引用
    // 最后由OutputCommitCoordinator的属性coordinatorRef持有OutputCommitCoordinatorEndpoint的引用
    val outputCommitCoordinator = mockOutputCommitCoordinator.getOrElse {
      new OutputCommitCoordinator(conf, isDriver)
    }
    val outputCommitCoordinatorRef = registerOrLookupEndpoint("OutputCommitCoordinator",
      new OutputCommitCoordinatorEndpoint(rpcEnv, outputCommitCoordinator))
    outputCommitCoordinator.coordinatorRef = Some(outputCommitCoordinatorRef)
    // 之前创建的对象就是为了此刻,到此,饱满的功能齐全的SparkEnv就可以初始化了.
    val envInstance = new SparkEnv(
      executorId,
      rpcEnv,
      serializer,
      closureSerializer,
      serializerManager,
      mapOutputTracker,
      shuffleManager,
      broadcastManager,
      blockManager,
      securityManager,
      metricsSystem,
      memoryManager,
      outputCommitCoordinator,
      conf)
    // 添加driver创建的临时文件夹的引用.stop()方法的调用会删除该文件夹,只需要为driver做这个操作.因为driver
    // 可能是作为一个服务来运行,如果停止时不删除该文件夹会创建过多的临时文件夹.
    // Add a reference to tmp dir created by driver, we will delete this tmp dir when stop() is
    // called, and we only need to do it for driver. Because driver may run as a service, and if we
    // don't delete this tmp dir when sc is stopped, then will create too many tmp dirs.
    if (isDriver) {
      val sparkFilesDir = Utils.createTempDir(Utils.getLocalDir(conf), "userFiles").getAbsolutePath
      envInstance.driverTmpDir = Some(sparkFilesDir)
    }

    envInstance
  }

  /**
   * 返回jvm信息的map表示，Spark属性，系统属性和类路径。
    * map键定义类别，map值将相应的属性表示为KV对的序列。
    * 这主要用于SparkListenerEnvironmentUpdate.<br>
    *
    * Return a map representation of jvm information, Spark properties, system properties, and
   * class paths. Map keys define the category, and map values represent the corresponding
   * attributes as a sequence of KV pairs. This is used mainly for SparkListenerEnvironmentUpdate.
   */
  private[spark]
  def environmentDetails(
      conf: SparkConf,
      schedulingMode: String,
      addedJars: Seq[String],
      addedFiles: Seq[String]): Map[String, Seq[(String, String)]] = {
    // JVM信息.java版本,java路径和Scala版本
    import Properties._
    val jvmInformation = Seq(
      ("Java Version", s"$javaVersion ($javaVendor)"),
      ("Java Home", javaHome),
      ("Scala Version", versionString)
    ).sorted

    // Spark properties
    // This includes the scheduling mode whether or not it is configured (used by SparkUI)
    // Spark属性,这包括调度模式是否配置（由SparkUI使用）
    val schedulerMode =
      if (!conf.contains("spark.scheduler.mode")) {
        Seq(("spark.scheduler.mode", schedulingMode))
      } else {
        Seq.empty[(String, String)]
      }
    val sparkProperties = (conf.getAll ++ schedulerMode).sorted

    // System properties that are not java classpaths
    // 系统属性
    val systemProperties = Utils.getSystemProperties.toSeq
    // 其他属性
    val otherProperties = systemProperties.filter { case (k, _) =>
      k != "java.class.path" && !k.startsWith("spark.")
    }.sorted

    // Class paths including all added jars and files
    // 添加的所有jar和文件
    val classPathEntries = javaClassPath
      .split(File.pathSeparator)
      .filterNot(_.isEmpty)
      .map((_, "System Classpath"))
    val addedJarsAndFiles = (addedJars ++ addedFiles).map((_, "Added By User"))
    val classPaths = (addedJarsAndFiles ++ classPathEntries).sorted

    Map[String, Seq[(String, String)]](
      "JVM Information" -> jvmInformation,
      "Spark Properties" -> sparkProperties,
      "System Properties" -> otherProperties,
      "Classpath Entries" -> classPaths)
  }
}
