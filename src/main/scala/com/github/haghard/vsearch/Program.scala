package com.github.haghard.vsearch

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.*
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown

import scala.jdk.CollectionConverters.IterableHasAsScala
import com.typesafe.config.ConfigRenderOptions
import org.rocksdb.RocksDB

import java.lang.management.ManagementFactory
import com.qxotic.jinfer.langchain4j.JinferEmbeddingModel

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

    // -Djinfer.batchCapacity=1500 -Djinfer.contextCapacity=1500
    // sys.props += "jinfer.batchCapacity"   -> "1500"
    // sys.props += "jinfer.contextCapacity" -> "1500"

    val httpAddress = sys.env.getOrElse("HTTP_ADDRESS", "::1")
    val httpPort    = sys.env.getOrElse("HTTP_PORT", "8080").toInt
    // println("imagecode: " + sys.props.get("org.graalvm.nativeimage.imagecode"))

    val modelPath: Path = Path.of(
      // "./local-models/LFM2.5-Embedding-350M-Q8_0.gguf"
      // "./local-models/LFM2.5-Embedding-350M-F16.gguf"
      "./local-models/Qwen3-Embedding-0.6B-Q8_0.gguf"
      // "./local-models/Qwen3-Embedding-0.6B-f16.gguf"
    )

    // val p: Path = java.nio.file.Paths.get("~/.cache/huggingface/hub")
    // JinferEmbeddingModel.builder().model(modelName).build()
    val embeddingModel: JinferEmbeddingModel =
      JinferEmbeddingModel
        .builder()
        .modelPath(modelPath)
        .build()

    val system =
      ActorSystem(Program(embeddingModel, embeddingModel.dimension(), httpAddress, httpPort), "vector-search")
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
         | EmbeddingModel: ${modelPath.toFile.toString} / ${embeddingModel.dimension()}
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

  def apply(
    embedder: JinferEmbeddingModel,
    dimension: Int,
    httpAddress: String,
    httpPort: Int
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val system = ctx.system
      implicit val logger = ctx.log
      import ctx.executionContext

      logger.info(system.printTree)
      logger.info(s"Binding on $httpAddress $httpPort")

      val obsHandle = Observability.init("vsearch")
      val router    = ctx.spawn(IndexRouter(obsHandle, embedder, dimension), "index-router")

      Http()
        .newServerAt(httpAddress, httpPort)
        .bind(httpRoutes(obsHandle, router))
        .failed
        .foreach { ex =>
          logger.error("Binding error", ex)
          obsHandle.shutdown()
          CoordinatedShutdown(system).run(Command.BindFailure)
        }
      Behaviors.empty
    }
}
