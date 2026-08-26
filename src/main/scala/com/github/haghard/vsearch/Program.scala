package com.github.haghard.vsearch

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.*
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.IterableHasAsScala
import com.typesafe.config.ConfigRenderOptions
import org.rocksdb.RocksDB

import java.lang.management.ManagementFactory
import java.nio.file.Path

/*
  val pp = new DefaultTimeBasedFileNamingAndTriggeringPolicy[ILoggingEvent]()
  // new TimeBasedFileNamingAndTriggeringPolicy[ILoggingEvent]()
  val rp = new ch.qos.logback.core.rolling.TimeBasedRollingPolicy[ILoggingEvent]()
  rp.setContext(context)
  rp.setTimeBasedFileNamingAndTriggeringPolicy(pp)
  rp.setFileNamePattern("logs/app-%d{yyyy-MM-dd}.log")
  rp.setTotalSizeCap(FileSize.valueOf("10mb"))
  rp.setMaxHistory(10)
  rp.setCleanHistoryOnStart()
  rp.start()

  val fileAppender = new ch.qos.logback.core.rolling.RollingFileAppender[ILoggingEvent]()
  fileAppender.setContext(context)
  fileAppender.setEncoder(encoder)
  fileAppender.setName("PROGRAMMATIC_FILE_APPENDER")
  fileAppender.setFile(s"logs/app-${System.currentTimeMillis()}.log")
  fileAppender.setRollingPolicy(rp)
  fileAppender.start()

 */

object Program extends Routes {

  sealed trait Command
  object Command {
    case object BindFailure extends CoordinatedShutdown.Reason
  }

  def main(args: Array[String]): Unit = {
    RocksDB.loadLibrary()

    val context: LoggerContext = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
    val encoder                = new PatternLayoutEncoder()
    encoder.setContext(context)
    encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
    // encoder.setPattern("[%date{ISO8601}] [%level] [%logger] [%X{akkaAddress}] [%marker] [%thread] - %msg%n")
    encoder.start()

    val fileAppender = new ch.qos.logback.core.FileAppender[ILoggingEvent]()
    fileAppender.setContext(context)
    fileAppender.setEncoder(encoder)
    fileAppender.setName("PROGRAMMATIC_FILE_APPENDER")
    fileAppender.setFile(s"logs/app-${System.currentTimeMillis()}.log")
    fileAppender.start()

    // Attach the appender to the root logger (or a specific logger)
    val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    rootLogger.addAppender(fileAppender)

    val httpAddress = sys.env.getOrElse("HTTP_ADDRESS", "::1")
    val httpPort    = sys.env.getOrElse("HTTP_PORT", "8080").toInt

    // println("imagecode: " + sys.props.get("org.graalvm.nativeimage.imagecode"))

    // val embedder = new EmbeddingGenerator(Path.of("./jina-embeddings-v2-base-en")) 768
    val embedder = new Embedder(Path.of("./all-MiniLM-L6-v2")) // 384

    val system = ActorSystem(Program(embedder, 384, httpAddress, httpPort), "vsearch")
    system.log.info(s"Loaded configuration... ${system.settings.config.root().render(ConfigRenderOptions.concise())}")

    val totalMemory = ManagementFactory
      .getOperatingSystemMXBean()
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalMemorySize()

    val jvmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments().asScala.mkString("[", ",", "]")
    val jvmInfo      =
      s"""
         | ★ ★ ★ ★ ★ ★ ★ ★ ★
         | PID: ${ProcessHandle.current().pid()}
         | Cores:${sys.runtime.availableProcessors()}
         | Memory: {
         |   Total=${sys.runtime.totalMemory() / 1000000}Mb, Max=${sys.runtime.maxMemory() / 1000000}Mb,
         |   Free=${sys.runtime.freeMemory() / 1000000}Mb, RAM=${totalMemory / 1000000}
         | }
         | JvmArgs: $jvmArguments
         | ★ ★ ★ ★ ★ ★ ★ ★ ★
         |""".stripMargin

    system.log.info(jvmInfo)
    System.getProperties().forEach((k: Any, v: Any) => (k, v) -> println(s"$k, $v"))
  }

  def apply(embedder: Embedder, dimensions: Int, httpAddress: String, httpPort: Int): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val system = ctx.system
      implicit val logger = ctx.log
      import ctx.executionContext

      logger.info(system.printTree)
      logger.info(s"Binding on $httpAddress $httpPort")

      val router = ctx.spawn(IndexRouter(embedder, dimensions), "index-router")

      Http()
        .newServerAt(httpAddress, httpPort)
        .bind(httpRoutes(router))
        .failed
        .foreach { ex =>
          logger.error("Binding error", ex)
          CoordinatedShutdown(system).run(Command.BindFailure)
        }
      Behaviors.empty
    }
}
