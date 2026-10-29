package com.github.haghard.vsearch

import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import com.qxotic.jinfer.langchain4j.JinferEmbeddingModel
import io.github.jbellis.jvector.graph.similarity.*
import io.github.jbellis.jvector.graph.*
import io.github.jbellis.jvector.util.Bits
import io.github.jbellis.jvector.vector.*
import io.github.jbellis.jvector.vector.types.*
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.metrics.{LongGauge, Meter}
import org.rocksdb.util.SizeUnit
import org.rocksdb.*

import java.nio.charset.StandardCharsets
import java.util
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

object SearchIndex {

  sealed trait Ack
  case object Ack extends Ack

  sealed trait Protocol {
    def index: String
    def reqId: String
  }

  final case class Init(index: String, reqId: String, replyTo: akka.actor.typed.ActorRef[Ack]) extends Protocol
  final case class Next(index: String, reqId: String, replyTo: akka.actor.typed.ActorRef[Ack], put: PutBatch)
      extends Protocol
  final case class Completed(index: String, reqId: String)                                 extends Protocol
  final case class Failed(index: String, reqId: String, ex: Throwable)                     extends Protocol
  final case class Put(index: String, reqId: String, text: String, replyTo: ActorRef[Ack]) extends Protocol
  final case class Get(index: String, reqId: String, limit: Int, query: String, replyTo: ActorRef[Option[String]])
      extends Protocol

  final case class PutBatch(index: String, reqId: String, reviews: Seq[String])

  final case class Gauges(sizeGauge: LongGauge, getLatency: LongGauge, putLatency: LongGauge)

  private def writeInt(i: Int): Array[Byte] = {
    val array = Array.ofDim[Byte](4)
    array(0) = (i >>> 24).toByte
    array(1) = (i >>> 16).toByte
    array(2) = (i >>> 8).toByte
    array(3) = i.toByte
    array
  }

  def apply(meter: Meter, embedder: JinferEmbeddingModel, dimensions: Int): Behavior[Protocol] =
    Behaviors.setup { implicit ctx =>
      val vts: VectorTypeSupport = VectorizationProvider.getInstance().getVectorTypeSupport()
      val vectors                = new util.ArrayList[VectorFloat[?]]()

      // val dvv                    = new DynamicVectorValues(dimensions)
      val dvv = new ListRandomAccessVectorValues(vectors, dimensions)

      val indexName = ctx.self.path.elements.last
      val dbPath    = s"./db/$indexName"
      val options   = new Options()
        .setCreateIfMissing(true)
        .setWriteBufferSize(10 * SizeUnit.KB)
        .setMaxWriteBufferNumber(3)
        .setBytesPerSync(3 * SizeUnit.MB)
        .setCompressionType(CompressionType.SNAPPY_COMPRESSION)
        .setCompactionStyle(CompactionStyle.UNIVERSAL)
        .setIncreaseParallelism(Runtime.getRuntime().availableProcessors())
      val db = RocksDB.open(options, dbPath)

      val indexBuilder = io.github.jbellis.jvector.graph.GraphIndexBuilder
        .builder(
          dvv,
          VectorSimilarityFunction.DOT_PRODUCT,
          32
        ) // I use DOT_PRODUCT because we L2-normalized all vectors
        .withBeamWidth(100)
        .withNeighborOverflow(1.2f)
        .withAlpha(1.2f)
        .build()

      val graphIndex = indexBuilder.getGraph()

      val indexSize =
        meter
          .gaugeBuilder(indexName + "-index-size")
          .setDescription("description")
          .setUnit("1")
          .ofLongs()
          .build()

      val get =
        meter
          .gaugeBuilder(indexName + "-index-get")
          .setUnit("1")
          .ofLongs()
          .build()

      val put =
        meter
          .gaugeBuilder(indexName + "-index-put")
          .setUnit("1")
          .ofLongs()
          .build()

      active(
        indexName,
        vectors,
        dimensions,
        vts,
        embedder,
        graphIndex,
        indexBuilder,
        db,
        Gauges(indexSize, get, put)
      )
    }

  def active(
    indexName: String,
    vectors: util.ArrayList[VectorFloat[?]],
    dimensions: Int,
    vts: VectorTypeSupport,
    embedder: JinferEmbeddingModel,
    graphIndex: ImmutableGraphIndex,
    builder: GraphIndexBuilder,
    db: RocksDB,
    gauges: Gauges
  )(implicit
    ctx: ActorContext[Protocol]
  ): Behavior[Protocol] =
    Behaviors.receiveMessage {
      case Init(_, _, replyTo) =>
        replyTo.tell(Ack)
        Behaviors.same
      case Next(_, _, replyTo, putN) =>
        putN.reviews.foreach { text =>
          val nextNodeId = vectors.size()
          val cnt        = embedder.embed(text).content()
          cnt.normalize()
          val embeddings = vts.createFloatVector(cnt.vector())
          vectors.add(nextNodeId, embeddings)
          builder.addGraphNode(nextNodeId, embeddings)
          db.put(writeInt(nextNodeId), text.getBytes(StandardCharsets.UTF_8))
        }
        gauges.sizeGauge.set(vectors.size())
        ctx.log.info("Index sizes:{}", vectors.size())
        replyTo.tell(Ack)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db, gauges)
      case Completed(_, _) =>
        ctx.log.info(
          "Index {} fully built [Doc number: {} / {}ramBytes]",
          indexName,
          vectors.size(),
          graphIndex.ramBytesUsed()
        )
        Behaviors.same
      case Failed(_, _, ex) =>
        ctx.log.error(ex.getMessage)
        Behaviors.stopped
      case Put(_, _, text, replyTo) =>
        val startTs    = System.nanoTime()
        val nextNodeId = vectors.size()
        ctx.log.info("Put at position {}", nextNodeId)
        val cnt = embedder.embed(text).content()
        cnt.normalize()
        val embeddings = vts.createFloatVector(cnt.vector())
        vectors.add(nextNodeId, embeddings)
        builder.addGraphNode(nextNodeId, embeddings)
        db.put(writeInt(nextNodeId), text.getBytes(StandardCharsets.UTF_8))
        val putResponseTime = (System.nanoTime - startTs) / (1_000L * 1_000L)
        gauges.sizeGauge.set(vectors.size())
        gauges.putLatency.set(putResponseTime, Attributes.of(AttributeKey.stringKey("units"), "millis"))
        replyTo.tell(Ack)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db, gauges)
      case Get(_, _, topN, query, replyTo) =>
        gauges.sizeGauge.set(vectors.size())
        val cnt = embedder.embed(query).content()
        cnt.normalize()
        ctx.log.info("Index sizes:{} / {} elements", graphIndex.ramBytesUsed(), vectors.size())
        val ssp = DefaultSearchScoreProvider.exact(
          vts.createFloatVector(cnt.vector()),
          VectorSimilarityFunction.DOT_PRODUCT, // we use PRODUCT due to vector normalization
          new ListRandomAccessVectorValues(vectors, dimensions)
        )
        search(graphIndex, db, ssp, query, replyTo, topN, gauges)
        Behaviors.same
    }

  def search(
    graphIndex: ImmutableGraphIndex,
    db: RocksDB,
    ssp: SearchScoreProvider,
    query: String,
    replyTo: ActorRef[Option[String]],
    topN: Int,
    gauges: Gauges
  ): Unit =
    Using.resource(new GraphSearcher(graphIndex)) { searcher =>
      val startTs         = System.nanoTime()
      val result          = searcher.search(ssp, topN, Bits.ALL)
      val indexSearchTime = (System.nanoTime - startTs) / 1_000L
      println(s"★ ★ ★ query=$query. Search took $indexSearchTime micro")
      gauges.getLatency.set(indexSearchTime, Attributes.of(AttributeKey.stringKey("units"), "micro"))
      val results = new ArrayBuffer[String](topN)
      for (ns <- result.getNodes()) {
        val nodeId = ns.node
        val score  = ns.score
        results.addOne(s"score:$score;nodeId:$nodeId")
        Option(db.get(writeInt(nodeId))).foreach { bytes =>
          println(s"id=$nodeId:score=$score - ${new String(bytes, StandardCharsets.UTF_8)}")
        }
      }
      println("★ ★ ★")

      if (results.isEmpty)
        replyTo.tell(None)
      else
        replyTo.tell(Some(results.mkString("[", ", ", "]")))
    }
}
