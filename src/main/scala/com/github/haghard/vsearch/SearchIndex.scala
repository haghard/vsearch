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
  final case class NextBatch(index: String, reqId: String, replyTo: akka.actor.typed.ActorRef[Ack], batch: Batch)
      extends Protocol
  final case class Completed(index: String, reqId: String)                                 extends Protocol
  final case class Failed(index: String, reqId: String, ex: Throwable)                     extends Protocol
  final case class Put(index: String, reqId: String, text: String, replyTo: ActorRef[Ack]) extends Protocol
  final case class Search(index: String, reqId: String, limit: Int, query: String, replyTo: ActorRef[Option[String]])
      extends Protocol

  final case class Batch(index: String, reqId: String, reviews: Seq[String])

  final case class Gauges(sizeGauge: LongGauge, getLatency: LongGauge, putLatency: LongGauge)

  private def writeKey(indexName: String, ind: Int): Array[Byte] = {
    val array = Array.ofDim[Byte](4)
    array(0) = (ind >>> 24).toByte
    array(1) = (ind >>> 16).toByte
    array(2) = (ind >>> 8).toByte
    array(3) = ind.toByte
    (indexName + ".").getBytes(StandardCharsets.UTF_8) ++ array
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
          VectorSimilarityFunction.DOT_PRODUCT, // I use DOT_PRODUCT because we L2-normalized all vectors
          32
        )
        .withBeamWidth(100)
        .withNeighborOverflow(1.2f)
        .withAlpha(1.2f)
        .build()

      val graphIndex = indexBuilder.getGraph()

      val indexSizeGauge =
        meter
          .gaugeBuilder(indexName + "-index-size")
          .setDescription("description")
          .setUnit("1")
          .ofLongs()
          .build()

      val getGauge =
        meter
          .gaugeBuilder(indexName + "-index-get")
          .setUnit("1")
          .ofLongs()
          .build()

      val putGauge =
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
        new WriteOptions().setSync(true),
        Gauges(indexSizeGauge, getGauge, putGauge)
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
    wrOps: WriteOptions,
    gauges: Gauges
  )(implicit
    ctx: ActorContext[Protocol]
  ): Behavior[Protocol] =
    Behaviors.receiveMessage {
      case Init(_, _, replyTo) =>
        replyTo.tell(Ack)
        Behaviors.same

      case NextBatch(indexName, _, replyTo, batch) =>
        val wb = new WriteBatch()
        batch.reviews.foreach { review =>
          val nextNodeId = vectors.size()
          val c          = embedder.embed(review).content()
          c.normalize()

          val embeddings = vts.createFloatVector(c.vector())
          vectors.add(nextNodeId, embeddings)
          wb.put(writeKey(indexName, nextNodeId), review.getBytes(StandardCharsets.UTF_8))
          builder.addGraphNode(nextNodeId, embeddings)
        }
        db.write(wrOps, wb)

        gauges.sizeGauge.set(vectors.size())
        ctx.log.info("Building index: size={}", vectors.size())
        replyTo.tell(Ack)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db, wrOps, gauges)

      case Completed(_, _) =>
        ctx.log.info(
          "Index {} fully built [num of docs: {} / {} ramBytes]",
          indexName,
          vectors.size(),
          graphIndex.ramBytesUsed()
        )
        Behaviors.same

      case Failed(_, _, ex) =>
        ctx.log.error(ex.getMessage)
        builder.close()
        graphIndex.close()
        Behaviors.stopped

      case Put(indexName, _, review, replyTo) =>
        val startTs    = System.currentTimeMillis()
        val nextNodeId = vectors.size()
        ctx.log.info("Put at position {}", nextNodeId)

        val c = embedder.embed(review).content()
        c.normalize()
        val embeddings = vts.createFloatVector(c.vector())

        vectors.add(nextNodeId, embeddings)
        builder.addGraphNode(nextNodeId, embeddings)
        db.put(writeKey(indexName, nextNodeId), review.getBytes(StandardCharsets.UTF_8))

        val putResponseTime = System.currentTimeMillis() - startTs
        gauges.sizeGauge.set(vectors.size())
        gauges.putLatency.set(putResponseTime, Attributes.of(AttributeKey.stringKey("units"), "millis"))
        replyTo.tell(Ack)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db, wrOps, gauges)

      case Search(indexName, _, topN, query, replyTo) =>
        gauges.sizeGauge.set(vectors.size())
        val c = embedder.embed(query).content()
        c.normalize()
        ctx.log.info("Index sizes:{} / {} elements", graphIndex.ramBytesUsed(), vectors.size())
        val ssp = DefaultSearchScoreProvider.exact(
          vts.createFloatVector(c.vector()),
          VectorSimilarityFunction.DOT_PRODUCT, // we use PRODUCT due to vector normalization
          new ListRandomAccessVectorValues(vectors, dimensions)
        )
        search(indexName, graphIndex, db, ssp, query, replyTo, topN, gauges)
        Behaviors.same
    }

  def search(
    indexName: String,
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
      val indexSearchTime = (System.nanoTime() - startTs) / 1_000L
      gauges.getLatency.set(indexSearchTime, Attributes.of(AttributeKey.stringKey("units"), "micro"))

      val graphIndexView = graphIndex.getView()

      @scala.annotation.nowarn
      val size = graphIndexView.size()
      println(s"★ ★ ★ query=$query. Index size:${size} Search took $indexSearchTime micro")

      val results = new ArrayBuffer[String](topN)
      for (ns <- result.getNodes()) {
        val nodeId = ns.node
        val score  = ns.score
        results.addOne(s"score:$score;nodeId:$nodeId")
        Option(db.get(writeKey(indexName, nodeId))).foreach { bytes =>
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
