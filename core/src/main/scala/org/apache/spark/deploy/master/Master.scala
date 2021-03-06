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

package org.apache.spark.deploy.master
// scalastyle:off
import java.text.SimpleDateFormat
import java.util.{Date, Locale}
import java.util.concurrent.{ScheduledFuture, TimeUnit}

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.Random

import org.apache.spark.{SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.{ApplicationDescription, DriverDescription,
  ExecutorState, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.deploy.master.MasterMessages._
import org.apache.spark.deploy.master.ui.MasterWebUI
import org.apache.spark.deploy.rest.StandaloneRestServer
import org.apache.spark.internal.Logging
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.rpc._
import org.apache.spark.serializer.{JavaSerializer, Serializer}
import org.apache.spark.util.{SparkUncaughtExceptionHandler, ThreadUtils, Utils}
/**
  * Master是local-cluster部署模式和Standalone部署模式的重要组件.
  * Master用于Worker管理,Application管理,Driver管理.负责对整个集群中所有资源的同一管理和分配.
  * 它接受各个Worker的注册,更新状态,心跳等消息,也介绍Driver和Application的注册.
  * Worker向Master注册时会携带自身的身份和资源信息(如,host,内核数,内存大小等),这些资源将按照一定
  * 的资源调度策略分配给Driver或Application.Master给Driver分配资源后,将向Worker发送启动Driver的命令,
  * 后者在接收到启动Driver的命令后启动Drvier.Master给Application分配了资源后,将向Wroker发送启动Executor的
  * 命令,后者在接收到启动Executor的命令后启动Executor.
  * Master接受Worker的状态更新消息,用于杀死不匹配的Driver或Application.
  * Worker向Master发送的心跳消息有两个目的:一是告知Master自己还活着,另外则是某个Master出现故障后,通过领导选举
  * 选择了其他Master负责对整个集群的管理,此时被激活的Master可能并没有缓存Worker的相关信息,
  * 因此需要告知WOrker重新向新Master注册.
  * */
private[deploy] class Master(
    override val rpcEnv: RpcEnv, // RPCENv
    address: RpcAddress,        // RPC的地址
    webUiPort: Int,             // webui的端口
    val securityMgr: SecurityManager,
    val conf: SparkConf)
  extends ThreadSafeRpcEndpoint with Logging with LeaderElectable {
  /** 包含一个现成的线程池,线程名称*/
  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")
  /** hadoop的配置*/
  private val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)

  // For application IDs
  /** 时间,用于appId*/
  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
  /** WOrker超时时间*/
  private val WORKER_TIMEOUT_MS = conf.getLong("spark.worker.timeout", 60) * 1000
  /** completedApps中最多可以保留的ApplicationInfo的数量的大小的限制*/
  private val RETAINED_APPLICATIONS = conf.getInt("spark.deploy.retainedApplications", 200)
  /** completedDriver中组多可以保留的DriverInfo的数量限制大小*/
  private val RETAINED_DRIVERS = conf.getInt("spark.deploy.retainedDrivers", 200)
  /** 从worker中溢出处于死亡状态的Worker所对应的WorkerInfo权重*/
  private val REAPER_ITERATIONS = conf.getInt("spark.dead.worker.persistence", 15)
  /** 恢复模式*/
  private val RECOVERY_MODE = conf.get("spark.deploy.recoveryMode", "NONE")
  /**Executor的最大重试数*/
  private val MAX_EXECUTOR_RETRIES = conf.getInt("spark.deploy.maxExecutorRetries", 10)
  /** 所有注册到Master的Worker信息*/
  val workers = new HashSet[WorkerInfo]
  /** ApplicationID与ApplicationInfo的映射关系*/
  val idToApp = new HashMap[String, ApplicationInfo]
  /** 正等待调度的Application所对应的ApplicationInfo的集合*/
  private val waitingApps = new ArrayBuffer[ApplicationInfo]
  /** 所有ApplicationInfo的集合*/
  val apps = new HashSet[ApplicationInfo]
  /** workerId和WorkerInfo的映射关系*/
  private val idToWorker = new HashMap[String, WorkerInfo]
  /** Worker的RpcEnv地址与WorkerInfo的映射关系*/
  private val addressToWorker = new HashMap[RpcAddress, WorkerInfo]
  /** RpcEndpointRef与ApplicationInfo的映射关系*/
  private val endpointToApp = new HashMap[RpcEndpointRef, ApplicationInfo]
  /** Application对应Driver的RpcEnv的地址与ApplicationInfo的映射关系*/
  private val addressToApp = new HashMap[RpcAddress, ApplicationInfo]
  /** 已经完成的Application的集合*/
  private val completedApps = new ArrayBuffer[ApplicationInfo]
  /** 下一个Application的Number.参与ApplicationId的生成*/
  private var nextAppNumber = 0
  /** 所有driverInfo的集合*/
  private val drivers = new HashSet[DriverInfo]
  /** 已经完成的DriverInfo的集合*/
  private val completedDrivers = new ArrayBuffer[DriverInfo]
  // Drivers currently spooled for scheduling
  /** 等待调度的Driver所对应的Driverinfo的集合*/
  private val waitingDrivers = new ArrayBuffer[DriverInfo]
  /** 下一个Driver号码*/
  private var nextDriverNumber = 0

  Utils.checkHost(address.host)
/** Master的度量系统*/
  private val masterMetricsSystem = MetricsSystem.createMetricsSystem("master", conf, securityMgr)
  /** app的度量系统*/
  private val applicationMetricsSystem = MetricsSystem.createMetricsSystem("applications", conf,
    securityMgr)
  /** Master度量的来源*/
  private val masterSource = new MasterSource(this)

  // After onStart, webUi will be set
  /** Master的WebUI,类型WebUI的子类MasterWebUI*/
  private var webUi: MasterWebUI = null
  /** master的公开地址,可通过Java系统环境变量SPARK_PUBLIC_DNS配置,默认是MasterRpcEnv的地址*/
  private val masterPublicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else address.host
  }
  /** Master的SparkURL*/
  private val masterUrl = address.toSparkURL
  /** Master的webUI的URL*/
  private var masterWebUiUrl: String = _
  /** Master所处的状态.STANDBY, ALIVE, RECOVERING, COMPLETING_RECOVERY这四种*/
  private var state = RecoveryState.STANDBY
  /** 持久化引擎*/
  private var persistenceEngine: PersistenceEngine = _
  /** 领导选举代理*/
  private var leaderElectionAgent: LeaderElectionAgent = _
  /** Master被选举为领导后,用于集群状态恢复的任务*/
  private var recoveryCompletionTask: ScheduledFuture[_] = _
  /** 检查worker超时的task*/
  private var checkForWorkerTimeOutTask: ScheduledFuture[_] = _

  // As a temporary workaround before better ways of configuring memory, we allow users to set
  // a flag that will perform round-robin scheduling across the nodes (spreading out each app
  // among all the nodes) instead of trying to consolidate each app onto a small # of nodes.
  /** 是否允许APplication能够在所有节点间调度.在所有节点间执行循环调度是Spark在实现更好的配置内存
    * 方法之前的临时解决方案,通过此方案可以避免Application总是固定在一小群节点上执行.可通过
    * spark.deploy.spreadOut配置,默认是true.
    * */
  private val spreadOutApps = conf.getBoolean("spark.deploy.spreadOut", true)

  // Default maxCores for applications that don't specify it (i.e. pass Int.MaxValue)
  /** 应用程序默认的最大内核数*/
  private val defaultCores = conf.getInt("spark.deploy.defaultCores", Int.MaxValue)
  /** SparkUI是否采用反向代理.*/
  val reverseProxy = conf.getBoolean("spark.ui.reverseProxy", false)
  if (defaultCores < 1) {
    throw new SparkException("spark.deploy.defaultCores must be positive")
  }

  // Alternative application submission gateway that is stable across Spark versions
  /** 是否提供REST服务以提交应用程序*/
  private val restServerEnabled = conf.getBoolean("spark.master.rest.enabled", true)
  /** REST服务的实例*/
  private var restServer: Option[StandaloneRestServer] = None
  /** REST服务绑定的端口*/
  private var restServerBoundPort: Option[Int] = None
  /** RpcEnv注册Master时,会触发onStart方法*/
  override def onStart(): Unit = {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    // 创建Master的Web UI绑定端口.
    webUi = new MasterWebUI(this, webUiPort)
    webUi.bind()
    // 拼接url
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort
    // 如果启动了SparkUI的反向代理,那么将masterWebUiUrl设置为反向代理url
    if (reverseProxy) {
      masterWebUiUrl = conf.get("spark.ui.reverseProxyUrl", masterWebUiUrl)
      webUi.addProxy()
      logInfo(s"Spark Master is acting as a reverse proxy. Master, Workers and " +
       s"Applications UIs are available at $masterWebUiUrl")
    }
    // 启动对Worker超时进行检查的定时任务
    checkForWorkerTimeOutTask = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(CheckForWorkerTimeOut)
      }
    }, 0, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    // 如果开启了REST服务
    if (restServerEnabled) {
      val port = conf.getInt("spark.master.rest.port", 6066)
      // 创建StandaloneRestServer
      restServer = Some(new StandaloneRestServer(address.host, port, conf, self, masterUrl))
    }
    // 启动rest服务
    restServerBoundPort = restServer.map(_.start())
    // 注册度量系统并启动
    masterMetricsSystem.registerSource(masterSource)
    masterMetricsSystem.start()
    applicationMetricsSystem.start()
    // Attach the master and app metrics servlet handler to the web ui after the metrics systems are
    // started.
    masterMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    applicationMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    // 获取序列化对象
    val serializer = new JavaSerializer(conf)
    // 根据模式创建持久化引擎和领导选举代理
    val (persistenceEngine_, leaderElectionAgent_) = RECOVERY_MODE match {
      case "ZOOKEEPER" =>
        logInfo("Persisting recovery state to ZooKeeper")
        // zk模式
        val zkFactory =
          new ZooKeeperRecoveryModeFactory(conf, serializer)
        (zkFactory.createPersistenceEngine(), zkFactory.createLeaderElectionAgent(this))
      case "FILESYSTEM" =>
        // 文件模式
        val fsFactory =
          new FileSystemRecoveryModeFactory(conf, serializer)
        (fsFactory.createPersistenceEngine(), fsFactory.createLeaderElectionAgent(this))
      case "CUSTOM" =>
        // 自定义模式
        val clazz = Utils.classForName(conf.get("spark.deploy.recoveryMode.factory"))
        val factory = clazz.getConstructor(classOf[SparkConf], classOf[Serializer])
          .newInstance(conf, serializer)
          .asInstanceOf[StandaloneRecoveryModeFactory]
        (factory.createPersistenceEngine(), factory.createLeaderElectionAgent(this))
      case _ =>
        (new BlackHolePersistenceEngine(), new MonarchyLeaderAgent(this))
    }
    // 然后为持久化引擎和选举代理成员对象赋值
    persistenceEngine = persistenceEngine_
    leaderElectionAgent = leaderElectionAgent_
  }

  override def onStop() {
    masterMetricsSystem.report()
    applicationMetricsSystem.report()
    // prevent the CompleteRecovery message sending to restarted master
    if (recoveryCompletionTask != null) {
      recoveryCompletionTask.cancel(true)
    }
    if (checkForWorkerTimeOutTask != null) {
      checkForWorkerTimeOutTask.cancel(true)
    }
    forwardMessageThread.shutdownNow()
    webUi.stop()
    restServer.foreach(_.stop())
    masterMetricsSystem.stop()
    applicationMetricsSystem.stop()
    persistenceEngine.close()
    leaderElectionAgent.stop()
  }
  /** 向自己发送ElectedLeader消息*/
  override def electedLeader() {
    self.send(ElectedLeader)
  }
  /** master没被选为领导,撤销领导关系*/
  override def revokedLeadership() {
    self.send(RevokedLeadership)
  }

  override def receive: PartialFunction[Any, Unit] = {
    case ElectedLeader =>
      // 从持久化引擎中读取存储的APPInfo,DriverInfo,WorkerInfo信息
      val (storedApps, storedDrivers, storedWorkers) = persistenceEngine.readPersistedData(rpcEnv)
      // 如果没有持久化信息
      state = if (storedApps.isEmpty && storedDrivers.isEmpty && storedWorkers.isEmpty) {
        // 将state设置为激活状态
        RecoveryState.ALIVE
      } else {
        // 否则设置为恢复状态
        RecoveryState.RECOVERING
      }
      logInfo("I have been elected leader! New state: " + state)
      // 如果是恢复状态
      if (state == RecoveryState.RECOVERING) {
        // 对整个集群状态进行恢复
        beginRecovery(storedApps, storedDrivers, storedWorkers)
        // 集群恢复完成后创建延时任务,向自己发送CompleteRecovery消息
        recoveryCompletionTask = forwardMessageThread.schedule(new Runnable {
          override def run(): Unit = Utils.tryLogNonFatalError {
            self.send(CompleteRecovery)
          }
        }, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }
    // 接收到CompleteRecovery表示集群恢复完成
    case CompleteRecovery => completeRecovery()
    // 撤销领导关系消息
    case RevokedLeadership =>
      logError("Leadership has been revoked -- master shutting down.")
      // 退出
      System.exit(0)
    // 注册worker消息
    case RegisterWorker(
      id, workerHost, workerPort, workerRef, cores, memory, workerWebUiUrl, masterAddress) =>
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format(
        workerHost, workerPort, cores, Utils.megabytesToString(memory)))
      if (state == RecoveryState.STANDBY) {
        // 如果master是standby,那么向worker回复standby消息
        workerRef.send(MasterInStandby)
      } else if (idToWorker.contains(id)) {
        // 如果idToWorker已经包含该worker,说明重复注册
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        // 否则
        // 创建workerInfo
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          workerRef, workerWebUiUrl)
        // 注册workerInfo,如果注册成功
        if (registerWorker(worker)) {
          // 持久化worker并回复,然后安排资源
          persistenceEngine.addWorker(worker)
          workerRef.send(RegisteredWorker(self, masterWebUiUrl, masterAddress))
          schedule()
        } else {
          // 注册失败向worker回复RegisterWorkerFailed
          val workerAddress = worker.endpoint.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }
    // 注册app
    case RegisterApplication(description, driver) =>
      // TODO Prevent repeated registrations from some driver
      if (state == RecoveryState.STANDBY) {
        // 如果当前状态是standby,不处理
        // ignore, don't send response
      } else {
        // 否则
        logInfo("Registering app " + description.name)
        // 创建appInfo
        val app = createApplication(description, driver)
        // 注册appInfo
        registerApplication(app)
        logInfo("Registered app " + description.name + " with ID " + app.id)
        // 持久化appInfo
        persistenceEngine.addApplication(app)
        // 向driver发送RegisteredApplication消息,表示注册成功
        driver.send(RegisteredApplication(app.id, self))
        // 安排资源
        schedule()
      }
    // executor报告自身状态改变
    case ExecutorStateChanged(appId, execId, state, message, exitStatus) =>
      // 获取appid对应的executordesc
      val execOption = idToApp.get(appId).flatMap(app => app.executors.get(execId))
      execOption match {
        case Some(exec) =>
          val appInfo = idToApp(appId)
          val oldState = exec.state
          exec.state = state
        // 对executor对应的不同状态进行处理
          if (state == ExecutorState.RUNNING) {
            assert(oldState == ExecutorState.LAUNCHING,
              s"executor $execId state transfer from $oldState to RUNNING is illegal")
            // 清零重试次数
            appInfo.resetRetryCount()
          }

          exec.application.driver.send(ExecutorUpdated(execId, state, message, exitStatus, false))

          if (ExecutorState.isFinished(state)) {
            // Remove this executor from the worker and app
            logInfo(s"Removing executor ${exec.fullId} because it is $state")
            // If an application has already finished, preserve its
            // state to display its information properly on the UI
            if (!appInfo.isFinished) {
              // 删除executor
              appInfo.removeExecutor(exec)
            }
            // 删除executor
            exec.worker.removeExecutor(exec)

            val normalExit = exitStatus == Some(0)
            // Only retry certain number of times so we don't go into an infinite loop.
            // Important note: this code path is not exercised by tests, so be very careful when
            // changing this `if` condition.
            if (!normalExit
                && appInfo.incrementRetryCount() >= MAX_EXECUTOR_RETRIES
                && MAX_EXECUTOR_RETRIES >= 0) { // < 0 disables this application-killing path
              val execs = appInfo.executors.values
              if (!execs.exists(_.state == ExecutorState.RUNNING)) {
                logError(s"Application ${appInfo.desc.name} with ID ${appInfo.id} failed " +
                  s"${appInfo.retryCount} times; removing it")
                // 删除app
                removeApplication(appInfo, ApplicationState.FAILED)
              }
            }
          }
          schedule()
        case None =>
          logWarning(s"Got status update for unknown executor $appId/$execId")
      }

    case DriverStateChanged(driverId, state, exception) =>
      state match {
        case DriverState.ERROR | DriverState.FINISHED | DriverState.KILLED | DriverState.FAILED =>
          removeDriver(driverId, state, exception)
        case _ =>
          throw new Exception(s"Received unexpected state update for driver $driverId: $state")
      }
    // worker向master发送心跳
    case Heartbeat(workerId, worker) =>
      idToWorker.get(workerId) match {
          // 获取workerInfo,匹配
        case Some(workerInfo) =>
          // 更新缓存中心跳时间
          workerInfo.lastHeartbeat = System.currentTimeMillis()
        case None =>
          // 如果没有缓存workerInfo
          if (workers.map(_.id).contains(workerId)) {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " Asking it to re-register.")
            // 发送重练消息
            worker.send(ReconnectWorker(masterUrl))
          } else {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " This worker was never registered, so ignoring the heartbeat.")
          }
      }

    case MasterChangeAcknowledged(appId) =>
      idToApp.get(appId) match {
        case Some(app) =>
          logInfo("Application has been re-registered: " + appId)
          app.state = ApplicationState.WAITING
        case None =>
          logWarning("Master change ack from unknown app: " + appId)
      }

      if (canCompleteRecovery) { completeRecovery() }

    case WorkerSchedulerStateResponse(workerId, executors, driverIds) =>
      idToWorker.get(workerId) match {
        case Some(worker) =>
          logInfo("Worker has been re-registered: " + workerId)
          worker.state = WorkerState.ALIVE

          val validExecutors = executors.filter(exec => idToApp.get(exec.appId).isDefined)
          for (exec <- validExecutors) {
            val app = idToApp.get(exec.appId).get
            val execInfo = app.addExecutor(worker, exec.cores, Some(exec.execId))
            worker.addExecutor(execInfo)
            execInfo.copyState(exec)
          }

          for (driverId <- driverIds) {
            drivers.find(_.id == driverId).foreach { driver =>
              driver.worker = Some(worker)
              driver.state = DriverState.RUNNING
              worker.addDriver(driver)
            }
          }
        case None =>
          logWarning("Scheduler state from unknown worker: " + workerId)
      }

      if (canCompleteRecovery) { completeRecovery() }
    // worker向master注册成功后发送
    case WorkerLatestState(workerId, executors, driverIds) =>
      // 根据workerId获取workerInfo
      idToWorker.get(workerId) match {
        case Some(worker) =>
          for (exec <- executors) {
            // 遍历每个ExecutorDescription,与WorkerInfo的executors中保存的
            // ExecutorDesc按照appID和execId匹配
            val executorMatches = worker.executors.exists {
              case (_, e) => e.application.id == exec.appId && e.id == exec.execId
            }
            // 如果匹配不成功,发送KillExecutor消息杀死executor
            if (!executorMatches) {
              // master doesn't recognize this executor. So just tell worker to kill it.
              worker.endpoint.send(KillExecutor(masterUrl, exec.appId, exec.execId))
            }
          }
          // 与workerInfo的drivers中保存的DriverId匹配,
          // 匹配不成功发送KillDriver消息杀死Driver
          for (driverId <- driverIds) {
            val driverMatches = worker.drivers.exists { case (id, _) => id == driverId }
            if (!driverMatches) {
              // master doesn't recognize this driver. So just tell worker to kill it.
              worker.endpoint.send(KillDriver(driverId))
            }
          }
        case None =>
          logWarning("Worker state from unknown worker: " + workerId)
      }

    case UnregisterApplication(applicationId) =>
      logInfo(s"Received unregister request from application $applicationId")
      idToApp.get(applicationId).foreach(finishApplication)
    // 检查超时worker
    case CheckForWorkerTimeOut =>
      timeOutDeadWorkers()

  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RequestSubmitDriver(description) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only accept driver submissions in ALIVE state."
        context.reply(SubmitDriverResponse(self, false, None, msg))
      } else {
        logInfo("Driver submitted " + description.command.mainClass)
        val driver = createDriver(description)
        persistenceEngine.addDriver(driver)
        waitingDrivers += driver
        drivers.add(driver)
        schedule()

        // TODO: It might be good to instead have the submission client poll the master to determine
        //       the current status of the driver. For now it's simply "fire and forget".

        context.reply(SubmitDriverResponse(self, true, Some(driver.id),
          s"Driver successfully submitted as ${driver.id}"))
      }

    case RequestKillDriver(driverId) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          s"Can only kill drivers in ALIVE state."
        context.reply(KillDriverResponse(self, driverId, success = false, msg))
      } else {
        logInfo("Asked to kill driver " + driverId)
        val driver = drivers.find(_.id == driverId)
        driver match {
          case Some(d) =>
            if (waitingDrivers.contains(d)) {
              waitingDrivers -= d
              self.send(DriverStateChanged(driverId, DriverState.KILLED, None))
            } else {
              // We just notify the worker to kill the driver here. The final bookkeeping occurs
              // on the return path when the worker submits a state change back to the master
              // to notify it that the driver was successfully killed.
              d.worker.foreach { w =>
                w.endpoint.send(KillDriver(driverId))
              }
            }
            // TODO: It would be nice for this to be a synchronous response
            val msg = s"Kill request for $driverId submitted"
            logInfo(msg)
            context.reply(KillDriverResponse(self, driverId, success = true, msg))
          case None =>
            val msg = s"Driver $driverId has already finished or does not exist"
            logWarning(msg)
            context.reply(KillDriverResponse(self, driverId, success = false, msg))
        }
      }

    case RequestDriverStatus(driverId) =>
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only request driver status in ALIVE state."
        context.reply(
          DriverStatusResponse(found = false, None, None, None, Some(new Exception(msg))))
      } else {
        (drivers ++ completedDrivers).find(_.id == driverId) match {
          case Some(driver) =>
            context.reply(DriverStatusResponse(found = true, Some(driver.state),
              driver.worker.map(_.id), driver.worker.map(_.hostPort), driver.exception))
          case None =>
            context.reply(DriverStatusResponse(found = false, None, None, None, None))
        }
      }

    case RequestMasterState =>
      context.reply(MasterStateResponse(
        address.host, address.port, restServerBoundPort,
        workers.toArray, apps.toArray, completedApps.toArray,
        drivers.toArray, completedDrivers.toArray, state))

    case BoundPortsRequest =>
      context.reply(BoundPortsResponse(address.port, webUi.boundPort, restServerBoundPort))
  // 如果Driver启用了ExecutorALlocationManager,那么ExecutorALlocationManager将发送该消息
    case RequestExecutors(appId, requestedTotal) =>
      context.reply(handleRequestExecutors(appId, requestedTotal))

    case KillExecutors(appId, executorIds) =>
      val formattedExecutorIds = formatExecutorIds(executorIds)
      context.reply(handleKillExecutors(appId, formattedExecutorIds))
  }

  override def onDisconnected(address: RpcAddress): Unit = {
    // The disconnected client could've been either a worker or an app; remove whichever it was
    logInfo(s"$address got disassociated, removing it.")
    addressToWorker.get(address).foreach(removeWorker(_, s"${address} got disassociated"))
    addressToApp.get(address).foreach(finishApplication)
    if (state == RecoveryState.RECOVERING && canCompleteRecovery) { completeRecovery() }
  }

  private def canCompleteRecovery =
    workers.count(_.state == WorkerState.UNKNOWN) == 0 &&
      apps.count(_.state == ApplicationState.UNKNOWN) == 0
  /** 开始恢复集群状态*/
  private def beginRecovery(storedApps: Seq[ApplicationInfo], storedDrivers: Seq[DriverInfo],
      storedWorkers: Seq[WorkerInfo]) {
    // 遍历ApplicationInfo
    for (app <- storedApps) {
      logInfo("Trying to recover app: " + app.id)
      try {
        // 注册ApplicationInfo
        registerApplication(app)
        // 设置app状态=UNKOWN
        app.state = ApplicationState.UNKNOWN
        // 向Driver发送MasterChanged消息,driver接收该消息后,将自身的master属性修改为当前Master的
        // RpcEndpointRef,并将alreadyDisconnected设置为false,
        // 最后向MasterMasterChangedAcknowledge消息
        app.driver.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("App " + app.id + " had exception on reconnect")
      }
    }
    // 遍历DriverInfo
    for (driver <- storedDrivers) {
      // Here we just read in the list of drivers. Any drivers associated with now-lost workers
      // will be re-launched when we detect that the worker is missing.
      // 添加到缓存中
      drivers += driver
    }
    // 遍历workerInfo
    for (worker <- storedWorkers) {
      logInfo("Trying to recover worker: " + worker.id)
      try {
        // 注册worker
        registerWorker(worker)
        // 设置UNNKOWN
        worker.state = WorkerState.UNKNOWN
        // 向worker发送masterchanged消息
        worker.endpoint.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("Worker " + worker.id + " had exception on reconnect")
      }
    }
  }
  /** 完成集群恢复后的操作*/
  private def completeRecovery() {
    // Ensure "only-once" recovery semantics using a short synchronization period.
    // 如果state不等于RECOVERING直接返回
    if (state != RecoveryState.RECOVERING) { return }
    // 将状态设置为COMPLETING_RECOVERY
    state = RecoveryState.COMPLETING_RECOVERY
    // 当开始恢复时会向worker或application发送MasterCHanged消息,他们接收到后
    // 会向新的Master发送重连请求,Master接收到就知道他们还活着,但是如果失联就仍然是UNNKOWN,
    // 所以可以确定它们死了,将其删除
    // Kill off any workers and apps that didn't respond to us.
    // 移除UNKNOWN状态的worker
    workers.filter(_.state == WorkerState.UNKNOWN).foreach(
      removeWorker(_, "Not responding for recovery"))
    // 移除UNKNOWN的app
    apps.filter(_.state == ApplicationState.UNKNOWN).foreach(finishApplication)

    // Update the state of recovered apps to RUNNING
    // 将状态改为running
    apps.filter(_.state == ApplicationState.WAITING).foreach(_.state = ApplicationState.RUNNING)

    // Reschedule drivers which were not claimed by any workers
    // 过滤出还没有分配worker的DriverInfo
    drivers.filter(_.worker.isEmpty).foreach { d =>
      logWarning(s"Driver ${d.id} was not found after master recovery")
      if (d.desc.supervise) {
        // 如果driver是被监管的
        logWarning(s"Re-launching ${d.id}")
        // 重新调度运行指定的driver
        relaunchDriver(d)
      } else {
        // 否则删除driver
        removeDriver(d.id, DriverState.ERROR, None)
        logWarning(s"Did not re-launch ${d.id} because it was not supervised")
      }
    }
    // 将master设置为alive
    state = RecoveryState.ALIVE
    // 进行资源调度
    schedule()
    logInfo("Recovery complete - resuming operations!")
  }

  /**
   * 安排将在worker上启动的executor.
    * Schedule executors to be launched on the workers.
   * Returns an array containing number of cores assigned to each worker.
   *
   * There are two modes of launching executors. The first attempts to spread out an application's
   * executors on as many workers as possible, while the second does the opposite (i.e. launch them
   * on as few workers as possible). The former is usually better for data locality purposes and is
   * the default.
   *
   * The number of cores assigned to each executor is configurable. When this is explicitly set,
   * multiple executors from the same application may be launched on the same worker if the worker
   * has enough cores and memory. Otherwise, each executor grabs all the cores available on the
   * worker by default, in which case only one executor per application may be launched on each
   * worker during one single schedule iteration.
   * Note that when `spark.executor.cores` is not set, we may still launch multiple executors from
   * the same application on the same worker. Consider appA and appB both have one executor running
   * on worker1, and appA.coresLeft > 0, then appB is finished and release all its cores on worker1,
   * thus for the next schedule iteration, appA launches a new executor that grabs all the free
   * cores on worker1, therefore we get multiple executors from appA running on worker1.
   *
   * It is important to allocate coresPerExecutor on each worker at a time (instead of 1 core
   * at a time). Consider the following example: cluster has 4 workers with 16 cores each.
   * User requests 3 executors (spark.cores.max = 48, spark.executor.cores = 16). If 1 core is
   * allocated at a time, 12 cores from each worker would be assigned to each executor.
   * Since 12 < 16, no executors would launch [SPARK-8881].
   */
  private def scheduleExecutorsOnWorkers(
      app: ApplicationInfo,
      usableWorkers: Array[WorkerInfo],
      spreadOutApps: Boolean): Array[Int] = {
    // app要求的每个Executor所需的内核数
    val coresPerExecutor = app.desc.coresPerExecutor
    // app要求的每个executor所需最小内核数
    val minCoresPerExecutor = coresPerExecutor.getOrElse(1)
    // 是否在每个Worker上分配一个Executor.没有默认为true
    val oneExecutorPerWorker = coresPerExecutor.isEmpty
    // app要求每个Executor所需的内存大小
    val memoryPerExecutor = app.desc.memoryPerExecutorMB
    // 可用的Worker数量.
    val numUsable = usableWorkers.length
    /** 用于保存每个worker给应用分配的executor个数*/
    val assignedCores = new Array[Int](numUsable) // Number of cores to give to each worker
    /** 用于保存每个Worker给应用分配的Executor数的数组.
      * 通过数组索引与usableWOrker是中的workerInfo对应*/
    val assignedExecutors = new Array[Int](numUsable) // Number of new executors on each worker
    /** 给app要分配的内核数.*/
    var coresToAssign = math.min(app.coresLeft, usableWorkers.map(_.coresFree).sum)

    /**
      * 返回指定worker是否可以为该app启动executor
      * Return whether the specified worker can launch an executor for this app. */
    def canLaunchExecutor(pos: Int): Boolean = {
      // 两个条件
      val keepScheduling = coresToAssign >= minCoresPerExecutor
      val enoughCores = usableWorkers(pos).coresFree - assignedCores(pos) >= minCoresPerExecutor

      // If we allow multiple executors per worker, then we can always launch new executors.
      // Otherwise, if there is already an executor on this worker, just give it more cores.
      // 如果每个workor上可以有多个Executor或者指定位置的workerInfo还没给app分配Executor
      val launchingNewExecutor = !oneExecutorPerWorker || assignedExecutors(pos) == 0
      if (launchingNewExecutor) {
        val assignedMemory = assignedExecutors(pos) * memoryPerExecutor
        val enoughMemory = usableWorkers(pos).memoryFree - assignedMemory >= memoryPerExecutor
        val underLimit = assignedExecutors.sum + app.executors.size < app.executorLimit
        // 还需要足够内存和满足限制.
        keepScheduling && enoughCores && enoughMemory && underLimit
      } else {
        // We're adding cores to an existing executor, so no need
        // to check memory and executor limits
        keepScheduling && enoughCores
      }
    }

    // Keep launching executors until no more workers can accommodate any
    // more executors, or if we have reached this application's limits
    // 获取所有可以运行Executor的Worker的索引
    var freeWorkers = (0 until numUsable).filter(canLaunchExecutor)
    // freeWorkers不为空
    while (freeWorkers.nonEmpty) {
      // 遍历freeworker的每个索引
      freeWorkers.foreach { pos =>
        var keepScheduling = true
        while (keepScheduling && canLaunchExecutor(pos)) {
          // 减去分配的核心
          coresToAssign -= minCoresPerExecutor
          // pos位置的workerInfo已分配内核数加上minCoresPerExecutor
          assignedCores(pos) += minCoresPerExecutor

          // If we are launching one executor per worker, then every iteration assigns 1 core
          // to the executor. Otherwise, every iteration assigns cores to a new executor.
          // 是否每个worker上一个executor
          if (oneExecutorPerWorker) {
            // assignedExecutors的索引为pos的值设置为1
            assignedExecutors(pos) = 1
          } else {
            // assignedExecutors的索引为pos的值加一
            assignedExecutors(pos) += 1
          }

          // Spreading out an application means spreading out its executors across as
          // many workers as possible. If we are not spreading out, then we should keep
          // scheduling executors on this worker until we use all of its resources.
          // Otherwise, just move on to the next worker.
          // 如果spreadOutApps,true会导致对pos位置上的workerInfo的资源调度提前结束,应用需要的
          // executor资源将会在其他workerInfo上调度.如果false,那么应用需要的
          // executor资源将会不断从pos位置的workerInfo上调度,直到pos位置的workerInfo
          // 资源使用完.
          if (spreadOutApps) {
            keepScheduling = false
          }
        }
      }
      freeWorkers = freeWorkers.filter(canLaunchExecutor)
    }
    // 返回
    assignedCores
  }

  /**
   * 启动worker上的executor
    * Schedule and launch executors on workers
   */
  private def startExecutorsOnWorkers(): Unit = {
    // Right now this is a very simple FIFO scheduler. We keep trying to fit in the first app
    // in the queue, then the second app, etc.
    // 遍历waitingApps
    for (app <- waitingApps) {
      // 获取每个Executor使用的核心数
      val coresPerExecutor = app.desc.coresPerExecutor.getOrElse(1)
      // If the cores left is less than the coresPerExecutor,the cores left will not be allocated
      // 如果app的剩余核心数大于等于coresPerExecutor
      if (app.coresLeft >= coresPerExecutor) {
        // Filter out workers that don't have enough resources to launch an executor
        // 找出worker中状态ALIVE,worker中空闲内存大于等于app要求每个executor内存大小并且
        // 空闲内核满足coresPerExecutor的所有WorkerInfo.然后根据内核倒排顺序.
        val usableWorkers = workers.toArray.filter(_.state == WorkerState.ALIVE)
          .filter(worker => worker.memoryFree >= app.desc.memoryPerExecutorMB &&
            worker.coresFree >= coresPerExecutor)
          .sortBy(_.coresFree).reverse
        // 在worker上进行Executor调度,返回worker上分配的内核数
        val assignedCores = scheduleExecutorsOnWorkers(app, usableWorkers, spreadOutApps)

        // Now that we've decided how many cores to allocate on each worker, let's allocate them
        for (pos <- 0 until usableWorkers.length if assignedCores(pos) > 0) {
          // 将worker上的资源分配给executor
          allocateWorkerResourceToExecutors(
            app, assignedCores(pos), app.desc.coresPerExecutor, usableWorkers(pos))
        }
      }
    }
  }

  /**
   * 将worker的资源分配给executor并运行
    * Allocate a worker's resources to one or more executors.
   * @param app the info of the application which the executors belong to
   * @param assignedCores number of cores on this worker for this application
   * @param coresPerExecutor number of cores per executor
   * @param worker the worker info
   */
  private def allocateWorkerResourceToExecutors(
      app: ApplicationInfo,
      assignedCores: Int,
      coresPerExecutor: Option[Int],
      worker: WorkerInfo): Unit = {
    // If the number of cores per executor is specified, we divide the cores assigned
    // to this worker evenly among the executors with no remainder.
    // Otherwise, we launch a single executor that grabs all the assignedCores on this worker.
    // 计算worker上要运行Executor的数量
    val numExecutors = coresPerExecutor.map { assignedCores / _ }.getOrElse(1)
    // 计算给Executor分配的内核数
    val coresToAssign = coresPerExecutor.getOrElse(assignedCores)
    for (i <- 1 to numExecutors) {
      val exec = app.addExecutor(worker, coresToAssign)
      // 在worker上创建运行executor
      launchExecutor(worker, exec)
      // 将app状态设置为running
      app.state = ApplicationState.RUNNING
    }
  }

  /**
   * 安排等待应用程序中当前可用的资源。 每次新app加入或资源可用性更改时，都会调用此方法。
    * Schedule the currently available resources among waiting apps. This method will be called
   * every time a new app joins or resource availability changes.
   */
  private def schedule(): Unit = {
    // 如果Master的状态不是ALIVE直接返回
    if (state != RecoveryState.ALIVE) {
      return
    }
    // Drivers take strict precedence over executors
    // 将ALive的worker洗牌,避免Driver总是分配给小部分的Worker
    val shuffledAliveWorkers = Random.shuffle(workers.toSeq.filter(_.state == WorkerState.ALIVE))
    // 存活worker数量
    val numWorkersAlive = shuffledAliveWorkers.size
    var curPos = 0
    // 遍历waitingDriver中的DriverInfo
    for (driver <- waitingDrivers.toList) { // iterate over a copy of waitingDrivers
      // We assign workers to each waiting driver in a round-robin fashion. For each driver, we
      // start from the last worker that was assigned a driver, and continue onwards until we have
      // explored all alive workers.
      var launched = false
      var numWorkersVisited = 0
      while (numWorkersVisited < numWorkersAlive && !launched) {
        val worker = shuffledAliveWorkers(curPos)
        numWorkersVisited += 1
        // 调处内存大小和内核数都满足Driver需要的
        if (worker.memoryFree >= driver.desc.mem && worker.coresFree >= driver.desc.cores) {
          // 运行Drvier
          launchDriver(worker, driver)
          // 从缓存中移除Drvier
          waitingDrivers -= driver
          launched = true
        }
        curPos = (curPos + 1) % numWorkersAlive
      }
    }
    // 在wroker上启动Executor
    startExecutorsOnWorkers()
  }
  /** 启动指定worker上的executor*/
  private def launchExecutor(worker: WorkerInfo, exec: ExecutorDesc): Unit = {
    logInfo("Launching executor " + exec.fullId + " on worker " + worker.id)
    // 添加executor信息
    worker.addExecutor(exec)
    // 发送LaunchExecutor的消息
    worker.endpoint.send(LaunchExecutor(masterUrl,
      exec.application.id, exec.id, exec.application.desc, exec.cores, exec.memory))
    // 向driver发送executorAdded消息
    exec.application.driver.send(
      ExecutorAdded(exec.id, worker.id, worker.hostPort, exec.cores, exec.memory))
  }
  /** 每个worker启动时都需要注册*/
  private def registerWorker(worker: WorkerInfo): Boolean = {
    // There may be one or more refs to dead workers on this same node (w/ different ID's),
    // remove them.
    // 从workers中移除host和port与要注册的WorkerInfo的host和port一样且dead的workerInfo
    workers.filter { w =>
      (w.host == worker.host && w.port == worker.port) && (w.state == WorkerState.DEAD)
    }.foreach { w =>
      workers -= w
    }

    val workerAddress = worker.endpoint.address
    // 如果addressToworker中包含相同的WorkerInfo
    if (addressToWorker.contains(workerAddress)) {
      val oldWorker = addressToWorker(workerAddress)
      // 并且状态是unknown
      if (oldWorker.state == WorkerState.UNKNOWN) {
        // A worker registering from UNKNOWN implies that the worker was restarted during recovery.
        // The old worker must thus be dead, so we will remove it and accept the new worker.
        // 移除workerInfo
        removeWorker(oldWorker, "Worker replaced by a new worker with same address")
      } else {
        logInfo("Attempted to re-register worker at same address: " + workerAddress)
        // 否则范湖ifalse
        return false
      }
    }
    // 更新缓存
    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }
  /** 删除worker*/
  private def removeWorker(worker: WorkerInfo, msg: String) {
    logInfo("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    // 将worker状态设置为dead
    worker.setState(WorkerState.DEAD)
    // 缓存中清理
    idToWorker -= worker.id
    addressToWorker -= worker.endpoint.address
      // worker中所有executor服务的app发送ExecutorUpdated消息
    for (exec <- worker.executors.values) {
      logInfo("Telling app of lost executor: " + exec.id)
      exec.application.driver.send(ExecutorUpdated(
        exec.id, ExecutorState.LOST, Some("worker lost"), None, workerLost = true))
      exec.state = ExecutorState.LOST
      // 调用app的removeExecutor方法
      exec.application.removeExecutor(exec)
    }
    // 遍历worker服务的driver
    for (driver <- worker.drivers.values) {
      if (driver.desc.supervise) {
        logInfo(s"Re-launching ${driver.id}")
        // 如果driver是被监管的,重新调度运行driver
        relaunchDriver(driver)
      } else {
        logInfo(s"Not re-launching ${driver.id} because it was not supervised")
        // 否则移除driver
        removeDriver(driver.id, DriverState.ERROR, None)
      }
    }
    logInfo(s"Telling app of lost worker: " + worker.id)
    apps.filterNot(completedApps.contains(_)).foreach { app =>
      // 发送WorkerRemoved消息
      app.driver.send(WorkerRemoved(worker.id, worker.host, msg))
    }
    // 删除持久化数据
    persistenceEngine.removeWorker(worker)
  }
  /** 重新调度运行指定的driver*/
  private def relaunchDriver(driver: DriverInfo) {
    // We must setup a new driver with a new driver id here, because the original driver may
    // be still running. Consider this scenario: a worker is network partitioned with master,
    // the master then relaunches driver driverID1 with a driver id driverID2, then the worker
    // reconnects to master. From this point on, if driverID2 is equal to driverID1, then master
    // can not distinguish the statusUpdate of the original driver and the newly relaunched one,
    // for example, when DriverStateChanged(driverID1, KILLED) arrives at master, master will
    // remove driverID1, so the newly relaunched driver disappears too. See SPARK-19900 for details.
    // 先删除
    removeDriver(driver.id, DriverState.RELAUNCHING, None)
    // 再创建driver
    val newDriver = createDriver(driver.desc)
    // 持久化
    persistenceEngine.addDriver(newDriver)
    // 更新缓存
    drivers.add(newDriver)
    waitingDrivers += newDriver
    // 安排资源
    schedule()
  }
  /** 创建appid,生成appinfo*/
  private def createApplication(desc: ApplicationDescription, driver: RpcEndpointRef):
      ApplicationInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    val appId = newApplicationId(date)
    new ApplicationInfo(now, appId, desc, date, driver, defaultCores)
  }
  /** 注册app*/
  private def registerApplication(app: ApplicationInfo): Unit = {
    val appAddress = app.driver.address
    if (addressToApp.contains(appAddress)) {
      // 如果已经注册了直接返回
      logInfo("Attempted to re-register application at same address: " + appAddress)
      return
    }
    //更新下缓存
    applicationMetricsSystem.registerSource(app.appSource)
    apps += app
    idToApp(app.id) = app
    endpointToApp(app.driver) = app
    addressToApp(appAddress) = app
    waitingApps += app
  }

  private def finishApplication(app: ApplicationInfo) {
    removeApplication(app, ApplicationState.FINISHED)
  }
  /** 移除master中缓存的app和app相关的driver信息*/
  def removeApplication(app: ApplicationInfo, state: ApplicationState.Value) {
    if (apps.contains(app)) {
      // 如果apps包含app,更新相关缓存
      logInfo("Removing app " + app.id)
      apps -= app
      idToApp -= app.id
      endpointToApp -= app.driver
      addressToApp -= app.driver.address
      // 如果completedApps元素数量大于等于RETAINED_APPLICATIONS
      if (completedApps.size >= RETAINED_APPLICATIONS) {
        val toRemove = math.max(RETAINED_APPLICATIONS / 10, 1)
        completedApps.take(toRemove).foreach { a =>
          // 度量系统删除appsource
          applicationMetricsSystem.removeSource(a.appSource)
        }
        // 删除头几个app信息
        completedApps.trimStart(toRemove)
      }
      completedApps += app // Remember it in our history
      waitingApps -= app
      // 杀死分配给app的executor
      for (exec <- app.executors.values) {
        killExecutor(exec)
      }
      app.markFinished(state)
      if (state != ApplicationState.FINISHED) {
        // 发送ApplicationRemoved消息
        app.driver.send(ApplicationRemoved(state.toString))
      }
      // 删除持久化数据
      persistenceEngine.removeApplication(app)
      // 安排资源
      schedule()

      // Tell all workers that the application has finished, so they can clean up any app state.
      workers.foreach { w =>
        // 发送app完成的消息
        w.endpoint.send(ApplicationFinished(app.id))
      }
    }
  }

  /**
   * Handle a request to set the target number of executors for this application.
   *
   * If the executor limit is adjusted upwards, new executors will be launched provided
   * that there are workers with sufficient resources. If it is adjusted downwards, however,
   * we do not kill existing executors until we explicitly receive a kill request.
   *
   * @return whether the application has previously registered with this Master.
   */
  private def handleRequestExecutors(appId: String, requestedTotal: Int): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requested to set total executors to $requestedTotal.")
        // 修改executor总数
        appInfo.executorLimit = requestedTotal
        // 安排资源
        schedule()
        true
      case None =>
        logWarning(s"Unknown application $appId requested $requestedTotal total executors.")
        false
    }
  }

  /**
   * Handle a kill request from the given application.
   *
   * This method assumes the executor limit has already been adjusted downwards through
   * a separate [[RequestExecutors]] message, such that we do not launch new executors
   * immediately after the old ones are removed.
   *
   * @return whether the application has previously registered with this Master.
   */
  private def handleKillExecutors(appId: String, executorIds: Seq[Int]): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requests to kill executors: " + executorIds.mkString(", "))
        val (known, unknown) = executorIds.partition(appInfo.executors.contains)
        known.foreach { executorId =>
          val desc = appInfo.executors(executorId)
          appInfo.removeExecutor(desc)
          killExecutor(desc)
        }
        if (unknown.nonEmpty) {
          logWarning(s"Application $appId attempted to kill non-existent executors: "
            + unknown.mkString(", "))
        }
        schedule()
        true
      case None =>
        logWarning(s"Unregistered application $appId requested us to kill executors!")
        false
    }
  }

  /**
   * Cast the given executor IDs to integers and filter out the ones that fail.
   *
   * All executors IDs should be integers since we launched these executors. However,
   * the kill interface on the driver side accepts arbitrary strings, so we need to
   * handle non-integer executor IDs just to be safe.
   */
  private def formatExecutorIds(executorIds: Seq[String]): Seq[Int] = {
    executorIds.flatMap { executorId =>
      try {
        Some(executorId.toInt)
      } catch {
        case e: NumberFormatException =>
          logError(s"Encountered executor with a non-integer ID: $executorId. Ignoring")
          None
      }
    }
  }

  /**
   * Ask the worker on which the specified executor is launched to kill the executor.
   */
  private def killExecutor(exec: ExecutorDesc): Unit = {
    exec.worker.removeExecutor(exec)
    exec.worker.endpoint.send(KillExecutor(masterUrl, exec.application.id, exec.id))
    exec.state = ExecutorState.KILLED
  }

  /** Generate a new app ID given an app's submission date */
  private def newApplicationId(submitDate: Date): String = {
    val appId = "app-%s-%04d".format(createDateFormat.format(submitDate), nextAppNumber)
    nextAppNumber += 1
    appId
  }

  /**
    * 检查删除任何超时的worker
    * Check for, and remove, any timed-out workers */
  private def timeOutDeadWorkers() {
    // Copy the workers into an array so we don't modify the hashset while iterating through it
    // 获取当前时间
    val currentTime = System.currentTimeMillis()
    // 过滤出所要超时的worker
    val toRemove = workers.filter(_.lastHeartbeat < currentTime - WORKER_TIMEOUT_MS).toArray
    for (worker <- toRemove) {
      if (worker.state != WorkerState.DEAD) {
        logWarning("Removing %s because we got no heartbeat in %d seconds".format(
          // 如果worker状态不等于DEAD
          worker.id, WORKER_TIMEOUT_MS / 1000))
        // 删除
        removeWorker(worker, s"Not receiving heartbeat for ${WORKER_TIMEOUT_MS / 1000} seconds")
      } else {
        // 如果已经dead了,达到一定时间将其从缓存中删除
        if (worker.lastHeartbeat < currentTime - ((REAPER_ITERATIONS + 1) * WORKER_TIMEOUT_MS)) {
          workers -= worker // we've seen this DEAD worker in the UI, etc. for long enough; cull it
        }
      }
    }
  }

  private def newDriverId(submitDate: Date): String = {
    val appId = "driver-%s-%04d".format(createDateFormat.format(submitDate), nextDriverNumber)
    nextDriverNumber += 1
    appId
  }

  private def createDriver(desc: DriverDescription): DriverInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    new DriverInfo(now, newDriverId(date), desc, date)
  }
  /** 启动driver*/
  private def launchDriver(worker: WorkerInfo, driver: DriverInfo) {
    logInfo("Launching driver " + driver.id + " on worker " + worker.id)
    // 在WrokerInfo中添加drirverInfo,建立彼此的联系
    worker.addDriver(driver)
    driver.worker = Some(worker)
    // 向worker发送LaunchDriver的消息,worker接收到消息后将运行Driver
    worker.endpoint.send(LaunchDriver(driver.id, driver.desc))
    // 修改DriverInfo的状态为Running
    driver.state = DriverState.RUNNING
  }
  /** 移除driver*/
  private def removeDriver(
      driverId: String,
      finalState: DriverState,
      exception: Option[Exception]) {
    // 找到指定driverinfo
    drivers.find(d => d.id == driverId) match {
      case Some(driver) =>
        logInfo(s"Removing driver: $driverId")
        // 删除指定driverinfo
        drivers -= driver
        // 如果completedDrivers元素数量大于等于RETAINED_DRIVERS
        if (completedDrivers.size >= RETAINED_DRIVERS) {
          val toRemove = math.max(RETAINED_DRIVERS / 10, 1)
          // 删除开头几个元素
          completedDrivers.trimStart(toRemove)
        }
        // 添加到completedDrivers
        completedDrivers += driver
        // 删除持久化信息
        persistenceEngine.removeDriver(driver)
        // 修改driver属性
        driver.state = finalState
        driver.exception = exception
        // woker中删除driver
        driver.worker.foreach(w => w.removeDriver(driver))
        // 安排资源
        schedule()
      case None =>
        logWarning(s"Asked to remove unknown driver: $driverId")
    }
  }
}

private[deploy] object Master extends Logging {
  val SYSTEM_NAME = "sparkMaster"
  val ENDPOINT_NAME = "Master"
  /** 可以通过进程方式启动,通过main方法可以作为单独的JVM进程启动*/
  def main(argStrings: Array[String]) {
    // 为当前线程设置未捕获异常的handler
    Thread.setDefaultUncaughtExceptionHandler(new SparkUncaughtExceptionHandler(
      exitOnUncaughtException = false))
    Utils.initDaemon(log)
    // 创建Sparkconf
    val conf = new SparkConf
    // 对main函数的参数解析
    val args = new MasterArguments(argStrings, conf)
    // 调用startRpcEnvAndEndpoint方法创建并启动Master
    val (rpcEnv, _, _) = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, conf)
    rpcEnv.awaitTermination()
  }

  /**
   * 启动master并且返回(Master的RPCENv,WebUi绑定端口,REST服务绑定的端口)的元组.
    * Start the Master and return a three tuple of:
   *   (1) The Master RpcEnv
   *   (2) The web UI bound port
   *   (3) The REST server bound port, if any
   */
  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      conf: SparkConf): (RpcEnv, Int, Option[Int]) = {
    // 创建SecurityManager
    val securityMgr = new SecurityManager(conf)
    // 创建RPcEnv
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, conf, securityMgr)
    // 创建Master,并且将Master注册到刚创建的RpcEnv中
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME,
      new Master(rpcEnv, rpcEnv.address, webUiPort, securityMgr, conf))
    // 通过Master的RpcEndpointRef向MasterBoundPortsRequst消息,并获得返回的
    // BoundPortsResponse
    val portsResponse = masterEndpoint.askSync[BoundPortsResponse](BoundPortsRequest)
    // 返回
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort)
  }
}
