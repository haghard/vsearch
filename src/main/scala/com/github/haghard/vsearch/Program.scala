package com.github.haghard.vsearch

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.*
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown
import scala.jdk.CollectionConverters.IterableHasAsScala
import com.typesafe.config.ConfigRenderOptions
import org.rocksdb.RocksDB

import java.lang.management.ManagementFactory
import java.nio.file.Path

object Program extends Routes {

  sealed trait Command
  object Command {
    case object BindFailure extends CoordinatedShutdown.Reason
  }

  def main(args: Array[String]): Unit = {
    RocksDB.loadLibrary()

    sys.props += "APP_VERSION"                 -> BuildInfo.version
    sys.props += "jvector.physical_core_count" -> (Runtime
      .getRuntime()
      .availableProcessors()
      .toFloat / 1.5).toInt.toString

    val httpAddress = sys.env.getOrElse("HTTP_ADDRESS", "::1")
    val httpPort    = sys.env.getOrElse("HTTP_PORT", "8080").toInt
    // println("imagecode: " + sys.props.get("org.graalvm.nativeimage.imagecode"))

    // val embedder = new EmbeddingGenerator(Path.of("./jina-embeddings-v2-base-en")) 768
    // val embedder = new Embedder(Path.of("./all-MiniLM-L12-v2"))
    val embedder = new Embedder(Path.of("./all-MiniLM-L6-v2")) // 384
    val system   = ActorSystem(Program(embedder, 384, httpAddress, httpPort), "vector-search")
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
         | Version: ${BuildInfo.version}
         | ★ ★ ★ ★ ★ ★ ★ ★ ★
         |""".stripMargin

    system.log.info(jvmInfo)
    // System.getProperties().forEach((k: Any, v: Any) => (k, v) -> println(s"$k, $v"))
  }

  def apply(embedder: Embedder, dimensions: Int, httpAddress: String, httpPort: Int): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val system = ctx.system
      implicit val logger = ctx.log
      import ctx.executionContext

      logger.info(system.printTree)
      logger.info(s"Binding on $httpAddress $httpPort")

      val obsHandle   = Observability.init("vsearch")
      val indexRouter = ctx.spawn(IndexRouter(obsHandle, embedder, dimensions), "index-router")

      Http()
        .newServerAt(httpAddress, httpPort)
        .bind(httpRoutes(obsHandle, indexRouter))
        .failed
        .foreach { ex =>
          logger.error("Binding error", ex)
          obsHandle.shutdown()
          CoordinatedShutdown(system).run(Command.BindFailure)
        }
      Behaviors.empty
    }
}
