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

package org.apache.spark.deploy

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.{List => JList}
import java.util.jar.JarFile

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.io.Source
import scala.util.Try

import org.apache.spark.deploy.SparkSubmitAction._
import org.apache.spark.launcher.SparkSubmitArgumentsParser
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.util.Utils


/**
  *
  * 解析和封装来自spark提交脚本的参数。
  * 环境参数用于测试
  * Parses and encapsulates arguments from the spark-submit script.
  * The env argument is used for testing.
  */
private[deploy] class SparkSubmitArguments(args: Seq[String], env: Map[String, String] = sys.env)
  extends SparkSubmitArgumentsParser {
  var master: String = null
  var deployMode: String = null
  var executorMemory: String = null
  var executorCores: String = null
  var totalExecutorCores: String = null
  var propertiesFile: String = null
  var driverMemory: String = null
  var driverExtraClassPath: String = null
  var driverExtraLibraryPath: String = null
  var driverExtraJavaOptions: String = null
  var queue: String = null
  var numExecutors: String = null
  var files: String = null
  var archives: String = null
  var mainClass: String = null
  var primaryResource: String = null
  var name: String = null
  var childArgs: ArrayBuffer[String] = new ArrayBuffer[String]()
  var jars: String = null
  var packages: String = null
  var repositories: String = null
  var ivyRepoPath: String = null
  var packagesExclusions: String = null
  var verbose: Boolean = false
  var isPython: Boolean = false
  var pyFiles: String = null
  var isR: Boolean = false
  var action: SparkSubmitAction = null
  val sparkProperties: HashMap[String, String] = new HashMap[String, String]()
  var proxyUser: String = null
  var principal: String = null
  var keytab: String = null

  // Standalone cluster mode only
  //这些参数只有standalone模式才可以使用
  var supervise: Boolean = false
  var driverCores: String = null
  var submissionToKill: String = null
  var submissionToRequestStatusFor: String = null
  var useRest: Boolean = true // used internally

  /** Default properties present in the currently defined defaults file.
    * 当前定义的默认文件中的默认属性。
    *
    * */
  lazy val defaultSparkProperties: HashMap[String, String] = {
    val defaultProperties = new HashMap[String, String]()
    // scalastyle:off println
    //如果verbose属性设置为true,则打印配置文件名称
    if (verbose) SparkSubmit.printStream.println(s"Using properties file: $propertiesFile")
    //对propertiesFIle进行遍历,根据设置文件名拿到属性map集合,将值放入defaultProperties中最后返回该变量.
    Option(propertiesFile).foreach { filename =>
      val properties = Utils.getPropertiesFromFile(filename)
      properties.foreach { case (k, v) =>
        defaultProperties(k) = v
      }
      // Property files may contain sensitive information, so redact before printing
      //因为配置文件中可能包含敏感信息,如密码之类的,所以在打印之前先编辑一下.其实就是将一些关键属性如url,passwd的值换成其他值
      if (verbose) {
        Utils.redact(properties).foreach { case (k, v) =>
          SparkSubmit.printStream.println(s"Adding default property: $k=$v")
        }
      }
    }
    // scalastyle:on println
    //返回默认属性对象Map
    defaultProperties
  }

  // Set parameters from command line arguments
  //就是将命令行参数args集合转换成JAVA对应的集合
  //方法在这使用会在创建对象时自动调用
  try {
    parse(args.asJava)
  } catch {
    case e: IllegalArgumentException =>
      SparkSubmit.printErrorAndExit(e.getMessage())
  }
  // Populate `sparkProperties` map from properties file
  //从属性文件中填充sparkProperties的map
  mergeDefaultSparkProperties()
  // Remove keys that don't start with "spark." from `sparkProperties`.
  //移除sparkProperties中不以spark.开头的键
  ignoreNonSparkProperties()
  // Use `sparkProperties` map along with env vars to fill in any missing parameters
  //使用sparkproperties和环境变量填充不存在的参数
  loadEnvironmentArguments()
  //验证参数
  validateArguments()

  /**
    * 将默认属性文件和--conf指定的属性的值合并
    * 当这个方法被调用时,sparkProperties已经包含了--conf指定的属性
    * Merge values from the default properties file with those specified through --conf.
    * When this is called, `sparkProperties` is already filled with configs from the latter.
    */
  private def mergeDefaultSparkProperties(): Unit = {
    // Use common defaults file, if not specified by user
    //如果用户未定义属性文件先加在默认属性
    propertiesFile = Option(propertiesFile).getOrElse(Utils.getDefaultPropertiesFile(env))
    // Honor --conf before the defaults file
    //--conf在defaultfile之前,就是如果--conf中有的参数会覆盖掉默认参数
    defaultSparkProperties.foreach { case (k, v) =>
      if (!sparkProperties.contains(k)) {
        sparkProperties(k) = v
      }
    }
  }

  /**
    * 删除sparkProperties中不以spark.开头的键
    * Remove keys that don't start with "spark." from `sparkProperties`.
    */
  private def ignoreNonSparkProperties(): Unit = {
    sparkProperties.foreach { case (k, v) =>
      if (!k.startsWith("spark.")) {
        sparkProperties -= k
        SparkSubmit.printWarning(s"Ignoring non-spark config property: $k=$v")
      }
    }
  }

  /**
    * 加载环境变量和spark属性
    * Load arguments from environment variables, Spark properties etc.
    */
  private def loadEnvironmentArguments(): Unit = {
    //master先获取master参数,orElse方法是如果为空,则设置为orElse方法参数的值
    //先从master参数中再从sparkProperties获取master....再从环境变量中获取,最后会在都没有情况下设置为local[*]
    //下面的参数都是,如果sparkProperties中没有,就从环境变量中拿,拿不到就是null
    master = Option(master)
      .orElse(sparkProperties.get("spark.master"))
      .orElse(env.get("MASTER"))
      .orNull
    driverExtraClassPath = Option(driverExtraClassPath)
      .orElse(sparkProperties.get("spark.driver.extraClassPath"))
      .orNull
    driverExtraJavaOptions = Option(driverExtraJavaOptions)
      .orElse(sparkProperties.get("spark.driver.extraJavaOptions"))
      .orNull
    driverExtraLibraryPath = Option(driverExtraLibraryPath)
      .orElse(sparkProperties.get("spark.driver.extraLibraryPath"))
      .orNull
    driverMemory = Option(driverMemory)
      .orElse(sparkProperties.get("spark.driver.memory"))
      .orElse(env.get("SPARK_DRIVER_MEMORY"))
      .orNull
    driverCores = Option(driverCores)
      .orElse(sparkProperties.get("spark.driver.cores"))
      .orNull
    executorMemory = Option(executorMemory)
      .orElse(sparkProperties.get("spark.executor.memory"))
      .orElse(env.get("SPARK_EXECUTOR_MEMORY"))
      .orNull
    executorCores = Option(executorCores)
      .orElse(sparkProperties.get("spark.executor.cores"))
      .orElse(env.get("SPARK_EXECUTOR_CORES"))
      .orNull
    totalExecutorCores = Option(totalExecutorCores)
      .orElse(sparkProperties.get("spark.cores.max"))
      .orNull
    name = Option(name).orElse(sparkProperties.get("spark.app.name")).orNull
    jars = Option(jars).orElse(sparkProperties.get("spark.jars")).orNull
    files = Option(files).orElse(sparkProperties.get("spark.files")).orNull
    ivyRepoPath = sparkProperties.get("spark.jars.ivy").orNull
    packages = Option(packages).orElse(sparkProperties.get("spark.jars.packages")).orNull
    packagesExclusions = Option(packagesExclusions)
      .orElse(sparkProperties.get("spark.jars.excludes")).orNull
    repositories = Option(repositories)
      .orElse(sparkProperties.get("spark.jars.repositories")).orNull
    deployMode = Option(deployMode)
      .orElse(sparkProperties.get("spark.submit.deployMode"))
      .orElse(env.get("DEPLOY_MODE"))
      .orNull
    numExecutors = Option(numExecutors)
      .getOrElse(sparkProperties.get("spark.executor.instances").orNull)
    queue = Option(queue).orElse(sparkProperties.get("spark.yarn.queue")).orNull
    keytab = Option(keytab).orElse(sparkProperties.get("spark.yarn.keytab")).orNull
    principal = Option(principal).orElse(sparkProperties.get("spark.yarn.principal")).orNull

    // Try to set main class from JAR if no --class argument is given
    //设置jar的main类,
    if (mainClass == null && !isPython && !isR && primaryResource != null) {
      val uri = new URI(primaryResource)
      val uriScheme = uri.getScheme()
      //如果URI的类型不是file就打印错误并退出,如果是的就获取mainClass的值
      uriScheme match {
        case "file" =>
          try {
            Utils.tryWithResource(new JarFile(uri.getPath)) { jar =>
              // Note that this might still return null if no main-class is set; we catch that later
              //这也可能得到null,所以补获异常
              mainClass = jar.getManifest.getMainAttributes.getValue("Main-Class")
            }
          } catch {
            case _: Exception =>
              SparkSubmit.printErrorAndExit(s"Cannot load main class from JAR $primaryResource")
          }
        case _ =>
          SparkSubmit.printErrorAndExit(
            s"Cannot load main class from JAR $primaryResource with URI $uriScheme. " +
              "Please specify a class through --class.")
      }
    }

    // Global defaults. These should be keep to minimum to avoid confusing behavior.
    //设置为全局默认的值.这应该放在最后来将行为的影响降到最低限度
    master = Option(master).getOrElse("local[*]")

    // In YARN mode, app name can be set via SPARK_YARN_APP_NAME (see SPARK-5222)
    //在yarn模式中,app名可能是由SPARK_YARN_APP_NAME来设置的
    if (master.startsWith("yarn")) {
      name = Option(name).orElse(env.get("SPARK_YARN_APP_NAME")).orNull
    }

    // Set name from main class if not given
    //如果没有指定appname,则通过main class名称设置
    name = Option(name).orElse(Option(mainClass)).orNull
    if (name == null && primaryResource != null) {
      name = Utils.stripDirectory(primaryResource)
    }

    // Action should be SUBMIT unless otherwise specified
    action = Option(action).getOrElse(SUBMIT)
  }

  /**
    * 确保必填属性存在,这个方法只被调用一次,所有的默认属性都回加载
    * Ensure that required fields exists. Call this only once all defaults are loaded. */
  private def validateArguments(): Unit = {
    action match {
      //提交任务配置参数的验证
      case SUBMIT => validateSubmitArguments()
      //结束任务的参数验证
      case KILL => validateKillArguments()
      //请求状态的参数的验证
      case REQUEST_STATUS => validateStatusRequestArguments()
    }
  }

  /**
    * 提交任务参数验证
    *
    */
  private def validateSubmitArguments(): Unit = {
    if (args.length == 0) {
      printUsageAndExit(-1)
    }
    if (primaryResource == null) {
      SparkSubmit.printErrorAndExit("Must specify a primary resource (JAR or Python or R file)")
    }
    if (mainClass == null && SparkSubmit.isUserJar(primaryResource)) {
      SparkSubmit.printErrorAndExit("No main class set in JAR; please specify one with --class")
    }
    if (driverMemory != null
      && Try(JavaUtils.byteStringAsBytes(driverMemory)).getOrElse(-1L) <= 0) {
      SparkSubmit.printErrorAndExit("Driver Memory must be a positive number")
    }
    if (executorMemory != null
      && Try(JavaUtils.byteStringAsBytes(executorMemory)).getOrElse(-1L) <= 0) {
      SparkSubmit.printErrorAndExit("Executor Memory cores must be a positive number")
    }
    if (executorCores != null && Try(executorCores.toInt).getOrElse(-1) <= 0) {
      SparkSubmit.printErrorAndExit("Executor cores must be a positive number")
    }
    if (totalExecutorCores != null && Try(totalExecutorCores.toInt).getOrElse(-1) <= 0) {
      SparkSubmit.printErrorAndExit("Total executor cores must be a positive number")
    }
    if (numExecutors != null && Try(numExecutors.toInt).getOrElse(-1) <= 0) {
      SparkSubmit.printErrorAndExit("Number of executors must be a positive number")
    }
    if (pyFiles != null && !isPython) {
      SparkSubmit.printErrorAndExit("--py-files given but primary resource is not a Python script")
    }

    if (master.startsWith("yarn")) {
      val hasHadoopEnv = env.contains("HADOOP_CONF_DIR") || env.contains("YARN_CONF_DIR")
      if (!hasHadoopEnv && !Utils.isTesting) {
        throw new Exception(s"When running with master '$master' " +
          "either HADOOP_CONF_DIR or YARN_CONF_DIR must be set in the environment.")
      }
    }

    if (proxyUser != null && principal != null) {
      SparkSubmit.printErrorAndExit("Only one of --proxy-user or --principal can be provided.")
    }
  }

  /**
    * 结束进程的参数验证
    * 如果master的url不是spark或mesos开头,则抛出异常并退出
    * 如果submissionToKill为空,也抛出异常退出
    */
  private def validateKillArguments(): Unit = {
    if (!master.startsWith("spark://") && !master.startsWith("mesos://")) {
      SparkSubmit.printErrorAndExit(
        "Killing submissions is only supported in standalone or Mesos mode!")
    }
    if (submissionToKill == null) {
      SparkSubmit.printErrorAndExit("Please specify a submission to kill.")
    }
  }

  /**
    * 验证请求状态的参数
    * 如果master的url不是spark或mesos开头,则抛出异常并退出
    * 如果submissionToKill为空,也抛出异常退出
    */
  private def validateStatusRequestArguments(): Unit = {
    if (!master.startsWith("spark://") && !master.startsWith("mesos://")) {
      SparkSubmit.printErrorAndExit(
        "Requesting submission statuses is only supported in standalone or Mesos mode!")
    }
    if (submissionToRequestStatusFor == null) {
      SparkSubmit.printErrorAndExit("Please specify a submission to request status for.")
    }
  }

  /**
    * 判断是否是独立模式集群
    * 如果master的url以spark开头并且部署模式是cluster则返回true
    *
    * @return
    */
  def isStandaloneCluster: Boolean = {
    master.startsWith("spark://") && deployMode == "cluster"
  }

  /**
    * 重写toString方法
    * 会打印出所有成员变量的值
    *
    * @return
    */
  override def toString: String = {
    s"""Parsed arguments:
       |  master                  $master
       |  deployMode              $deployMode
       |  executorMemory          $executorMemory
       |  executorCores           $executorCores
       |  totalExecutorCores      $totalExecutorCores
       |  propertiesFile          $propertiesFile
       |  driverMemory            $driverMemory
       |  driverCores             $driverCores
       |  driverExtraClassPath    $driverExtraClassPath
       |  driverExtraLibraryPath  $driverExtraLibraryPath
       |  driverExtraJavaOptions  $driverExtraJavaOptions
       |  supervise               $supervise
       |  queue                   $queue
       |  numExecutors            $numExecutors
       |  files                   $files
       |  pyFiles                 $pyFiles
       |  archives                $archives
       |  mainClass               $mainClass
       |  primaryResource         $primaryResource
       |  name                    $name
       |  childArgs               [${childArgs.mkString(" ")}]
       |  jars                    $jars
       |  packages                $packages
       |  packagesExclusions      $packagesExclusions
       |  repositories            $repositories
       |  verbose                 $verbose
       |
    |Spark properties used, including those specified through
       | --conf and those from the properties file $propertiesFile:
       |${Utils.redact(sparkProperties).mkString("  ", "\n  ", "\n")}
    """.stripMargin
  }

  /**
    * 根据用户选项填充属性值,除非抛出异常,都返回true
    * 在handle函数中,如果action有值,不能设置为其他值
    * Fill in values by parsing user options. */
  override protected def handle(opt: String, value: String): Boolean = {
    opt match {
      case NAME =>
        name = value

      case MASTER =>
        master = value

      case CLASS =>
        mainClass = value

      case DEPLOY_MODE =>
        if (value != "client" && value != "cluster") {
          SparkSubmit.printErrorAndExit("--deploy-mode must be either \"client\" or \"cluster\"")
        }
        deployMode = value

      case NUM_EXECUTORS =>
        numExecutors = value

      case TOTAL_EXECUTOR_CORES =>
        totalExecutorCores = value

      case EXECUTOR_CORES =>
        executorCores = value

      case EXECUTOR_MEMORY =>
        executorMemory = value

      case DRIVER_MEMORY =>
        driverMemory = value

      case DRIVER_CORES =>
        driverCores = value

      case DRIVER_CLASS_PATH =>
        driverExtraClassPath = value

      case DRIVER_JAVA_OPTIONS =>
        driverExtraJavaOptions = value

      case DRIVER_LIBRARY_PATH =>
        driverExtraLibraryPath = value

      case PROPERTIES_FILE =>
        propertiesFile = value

      case KILL_SUBMISSION =>
        submissionToKill = value
        if (action != null) {
          SparkSubmit.printErrorAndExit(s"Action cannot be both $action and $KILL.")
        }
        action = KILL

      case STATUS =>
        submissionToRequestStatusFor = value
        if (action != null) {
          SparkSubmit.printErrorAndExit(s"Action cannot be both $action and $REQUEST_STATUS.")
        }
        action = REQUEST_STATUS

      case SUPERVISE =>
        supervise = true

      case QUEUE =>
        queue = value

      case FILES =>
        files = Utils.resolveURIs(value)

      case PY_FILES =>
        pyFiles = Utils.resolveURIs(value)

      case ARCHIVES =>
        archives = Utils.resolveURIs(value)

      case JARS =>
        jars = Utils.resolveURIs(value)

      case PACKAGES =>
        packages = value

      case PACKAGES_EXCLUDE =>
        packagesExclusions = value

      case REPOSITORIES =>
        repositories = value

      case CONF =>
        val (confName, confValue) = SparkSubmit.parseSparkConfProperty(value)
        sparkProperties(confName) = confValue

      case PROXY_USER =>
        proxyUser = value

      case PRINCIPAL =>
        principal = value

      case KEYTAB =>
        keytab = value

      case HELP =>
        printUsageAndExit(0)

      case VERBOSE =>
        verbose = true

      case VERSION =>
        SparkSubmit.printVersionAndExit()

      case USAGE_ERROR =>
        printUsageAndExit(1)

      case _ =>
        throw new IllegalArgumentException(s"Unexpected argument '$opt'.")
    }
    true
  }

  /**
    *处理不能识别的命令行选项 第一个选项按照pimaryresource的方式处理
    * 其他的选项按照应用参数(application arguments)方式处理
    *
    * Handle unrecognized command line options.
    *
    * The first unrecognized option is treated as the "primary resource". Everything else is
    * treated as application arguments.
    */
  override protected def handleUnknown(opt: String): Boolean = {
    if (opt.startsWith("-")) {
      SparkSubmit.printErrorAndExit(s"Unrecognized option '$opt'.")
    }

    primaryResource =
      if (!SparkSubmit.isShell(opt) && !SparkSubmit.isInternal(opt)) {
        Utils.resolveURI(opt).toString
      } else {
        opt
      }
    isPython = SparkSubmit.isPython(opt)
    isR = SparkSubmit.isR(opt)
    false
  }

  /**
    * 处理额外参数
    * 将额外参数添加到childArgs成员变量中
    * @param extra
    */
  override protected def handleExtraArgs(extra: JList[String]): Unit = {
    childArgs ++= extra.asScala
  }

  /**
    * 打印使用参数
    *
    * @param exitCode 退出码
    * @param unknownParam 如果该参数(未知参数)不等于null,则打印
    */
  private def printUsageAndExit(exitCode: Int, unknownParam: Any = null): Unit = {
    // scalastyle:off println
    val outStream = SparkSubmit.printStream
    if (unknownParam != null) {
      outStream.println("Unknown/unsupported param " + unknownParam)
    }
    val command = sys.env.get("_SPARK_CMD_USAGE").getOrElse(
      """Usage: spark-submit [options] <app jar | python file | R file> [app arguments]
        |Usage: spark-submit --kill [submission ID] --master [spark://...]
        |Usage: spark-submit --status [submission ID] --master [spark://...]
        |Usage: spark-submit run-example [options] example-class [example args]""".stripMargin)
    outStream.println(command)

    val mem_mb = Utils.DEFAULT_DRIVER_MEM_MB
    outStream.println(
      s"""
         |Options:
         |  --master MASTER_URL         spark://host:port, mesos://host:port, yarn,
         |                              k8s://https://host:port, or local (Default: local[*]).
         |  --deploy-mode DEPLOY_MODE   Whether to launch the driver program locally ("client") or
         |                              on one of the worker machines inside the cluster ("cluster")
         |                              (Default: client).
         |  --class CLASS_NAME          Your application's main class (for Java / Scala apps).
         |  --name NAME                 A name of your application.
         |  --jars JARS                 Comma-separated list of jars to include on the driver
         |                              and executor classpaths.
         |  --packages                  Comma-separated list of maven coordinates of jars to include
         |                              on the driver and executor classpaths. Will search the local
         |                              maven repo, then maven central and any additional remote
         |                              repositories given by --repositories. The format for the
         |                              coordinates should be groupId:artifactId:version.
         |  --exclude-packages          Comma-separated list of groupId:artifactId, to exclude while
         |                              resolving the dependencies provided in --packages to avoid
         |                              dependency conflicts.
         |  --repositories              Comma-separated list of additional remote repositories to
         |                              search for the maven coordinates given with --packages.
         |  --py-files PY_FILES         Comma-separated list of .zip, .egg, or .py files to place
         |                              on the PYTHONPATH for Python apps.
         |  --files FILES               Comma-separated list of files to be placed in the working
         |                              directory of each executor. File paths of these files
         |                              in executors can be accessed via SparkFiles.get(fileName).
         |
        |  --conf PROP=VALUE           Arbitrary Spark configuration property.
         |  --properties-file FILE      Path to a file from which to load extra properties. If not
         |                              specified, this will look for conf/spark-defaults.conf.
         |
        |  --driver-memory MEM         Memory for driver (e.g. 1000M, 2G) (Default: ${mem_mb}M).
         |  --driver-java-options       Extra Java options to pass to the driver.
         |  --driver-library-path       Extra library path entries to pass to the driver.
         |  --driver-class-path         Extra class path entries to pass to the driver. Note that
         |                              jars added with --jars are automatically included in the
         |                              classpath.
         |
        |  --executor-memory MEM       Memory per executor (e.g. 1000M, 2G) (Default: 1G).
         |
        |  --proxy-user NAME           User to impersonate when submitting the application.
         |                              This argument does not work with --principal / --keytab.
         |
        |  --help, -h                  Show this help message and exit.
         |  --verbose, -v               Print additional debug output.
         |  --version,                  Print the version of current Spark.
         |
        | Cluster deploy mode only:
         |  --driver-cores NUM          Number of cores used by the driver, only in cluster mode
         |                              (Default: 1).
         |
        | Spark standalone or Mesos with cluster deploy mode only:
         |  --supervise                 If given, restarts the driver on failure.
         |  --kill SUBMISSION_ID        If given, kills the driver specified.
         |  --status SUBMISSION_ID      If given, requests the status of the driver specified.
         |
        | Spark standalone and Mesos only:
         |  --total-executor-cores NUM  Total cores for all executors.
         |
        | Spark standalone and YARN only:
         |  --executor-cores NUM        Number of cores per executor. (Default: 1 in YARN mode,
         |                              or all available cores on the worker in standalone mode)
         |
        | YARN-only:
         |  --queue QUEUE_NAME          The YARN queue to submit to (Default: "default").
         |  --num-executors NUM         Number of executors to launch (Default: 2).
         |                              If dynamic allocation is enabled, the initial number of
         |                              executors will be at least NUM.
         |  --archives ARCHIVES         Comma separated list of archives to be extracted into the
         |                              working directory of each executor.
         |  --principal PRINCIPAL       Principal to be used to login to KDC, while running on
         |                              secure HDFS.
         |  --keytab KEYTAB             The full path to the file that contains the keytab for the
         |                              principal specified above. This keytab will be copied to
         |                              the node running the Application Master via the Secure
         |                              Distributed Cache, for renewing the login tickets and the
         |                              delegation tokens periodically.
      """.stripMargin
    )
    //如果是SqlShell模式,打印CLI 选项
    if (SparkSubmit.isSqlShell(mainClass)) {
      outStream.println("CLI options:")
      outStream.println(getSqlShellOptions())
    }
    // scalastyle:on println
  //退出
    SparkSubmit.exitFn(exitCode)
  }

  /**
    * 运行SQL客户端时添加--help选项并且捕获到输出,然后过滤结果来删除不想要的行.
    * 因为CLI会调用System.exit().我们设置一个安全管理来避免工作中直接结束CLI.
    * Run the Spark SQL CLI main class with the "--help" option and catch its output. Then filter
    * the results to remove unwanted lines.
    *
    * Since the CLI will call `System.exit()`, we install a security manager to prevent that call
    * from working, and restore the original one afterwards.
    */
  private def getSqlShellOptions(): String = {
    val currentOut = System.out
    val currentErr = System.err
    val currentSm = System.getSecurityManager()
    try {
      val out = new ByteArrayOutputStream()
      val stream = new PrintStream(out)
      System.setOut(stream)
      System.setErr(stream)

      val sm = new SecurityManager() {
        override def checkExit(status: Int): Unit = {
          throw new SecurityException()
        }

        override def checkPermission(perm: java.security.Permission): Unit = {}
      }
      System.setSecurityManager(sm)
      //反射调用HELP方法(--help)
      try {
        Utils.classForName(mainClass).getMethod("main", classOf[Array[String]])
          .invoke(null, Array(HELP))
      } catch {
        case e: InvocationTargetException =>
          // Ignore SecurityException, since we throw it above.
          if (!e.getCause().isInstanceOf[SecurityException]) {
            throw e
          }
      }

      stream.flush()

      // Get the output and discard any unnecessary lines from it.
      //过滤掉不相关的行
      Source.fromString(new String(out.toByteArray(), StandardCharsets.UTF_8)).getLines
        .filter { line =>
          !line.startsWith("log4j") && !line.startsWith("usage")
        }
        .mkString("\n")
    } finally {
      System.setSecurityManager(currentSm)
      System.setOut(currentOut)
      System.setErr(currentErr)
    }
  }
}