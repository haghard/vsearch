package com.github.haghard.vsearch

import akka.Done
import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import io.github.jbellis.jvector.graph.similarity.*
import io.github.jbellis.jvector.graph.*
import io.github.jbellis.jvector.util.Bits
import io.github.jbellis.jvector.vector.*
import io.github.jbellis.jvector.vector.types.*
import org.rocksdb.util.SizeUnit
import org.rocksdb.*

import java.nio.charset.StandardCharsets
import java.util
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

object HNSWIndex {

  sealed trait Protocol {
    def index: String

    def reqId: String
  }

  object Protocol {
    final case class Put(index: String, reqId: String, text: String, replyTo: ActorRef[Done]) extends Protocol

    final case class PutN(index: String, reqId: String, texts: Seq[String], replyTo: ActorRef[Done]) extends Protocol

    final case class Get(index: String, reqId: String, limit: Int, query: String, replyTo: ActorRef[Option[String]])
        extends Protocol
  }

  private def writeInt(i: Int): Array[Byte] = {
    val array = Array.ofDim[Byte](4)
    array(0) = (i >>> 24).toByte
    array(1) = (i >>> 16).toByte
    array(2) = (i >>> 8).toByte
    array(3) = i.toByte
    array
  }

  def apply(embedder: Embedder, dimensions: Int): Behavior[Protocol] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timers =>
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
          .setBytesPerSync(1 * SizeUnit.MB)
          .setCompressionType(CompressionType.SNAPPY_COMPRESSION)
          .setCompactionStyle(CompactionStyle.UNIVERSAL)
          .setIncreaseParallelism(Runtime.getRuntime().availableProcessors())
        val db = RocksDB.open(options, dbPath)

        // I use DOT_PRODUCT because we L2-normalized all vectors
        val indexBuilder = new GraphIndexBuilder(dvv, VectorSimilarityFunction.DOT_PRODUCT, 32, 100, 1.2f, 1.2f, true)
        val graphIndex   = indexBuilder.getGraph()
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, indexBuilder, db)
      }
    }

  def active(
    indexName: String,
    vectors: util.ArrayList[VectorFloat[?]],
    dimensions: Int,
    vts: VectorTypeSupport,
    embedder: Embedder,
    graphIndex: ImmutableGraphIndex,
    builder: GraphIndexBuilder,
    db: RocksDB
  )(implicit
    ctx: ActorContext[Protocol],
    sch: TimerScheduler[Protocol]
  ): Behavior[Protocol] =
    Behaviors.receiveMessage {
      case Protocol.Put(_, _, text, replyTo) =>
        val nextNodeId = vectors.size()
        ctx.log.info("Put {} at position {}", text, nextNodeId)
        val bts        = embedder.embed(text)
        val embeddings = vts.createFloatVector(bts)
        vectors.add(nextNodeId, embeddings)
        builder.addGraphNode(nextNodeId, embeddings)
        db.put(writeInt(nextNodeId), text.getBytes(StandardCharsets.UTF_8))
        replyTo.tell(Done)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db)
      case Protocol.PutN(_, _, texts, replyTo) =>
        texts.foreach { text =>
          val nextNodeId = vectors.size()
          val bts        = embedder.embed(text)
          val embeddings = vts.createFloatVector(bts)
          vectors.add(nextNodeId, embeddings)
          builder.addGraphNode(nextNodeId, embeddings)
          db.put(writeInt(nextNodeId), text.getBytes(StandardCharsets.UTF_8))
        }
        replyTo.tell(Done)
        active(indexName, vectors, dimensions, vts, embedder, graphIndex, builder, db)

      case Protocol.Get(_, _, topN, query, replyTo) =>
        ctx.log.info("Index sizes:{} bts/{} elements", graphIndex.ramBytesUsed(), vectors.size())
        val ssp = DefaultSearchScoreProvider.exact(
          vts.createFloatVector(embedder.embed(query)),
          VectorSimilarityFunction.DOT_PRODUCT,
          new ListRandomAccessVectorValues(vectors, dimensions)
        )
        search(graphIndex, db, ssp, query, replyTo, topN)
        Behaviors.same
    }

  def search(
    graphIndex: ImmutableGraphIndex,
    db: RocksDB,
    ssp: SearchScoreProvider,
    query: String,
    replyTo: ActorRef[Option[String]],
    topN: Int
  ): Unit =
    Using.resource(new GraphSearcher(graphIndex)) { searcher =>
      val startTs = System.nanoTime()
      val result  = searcher.search(ssp, topN, Bits.ALL)

      println(s"★ ★ ★ query=$query. Search took ${(System.nanoTime - startTs) / 1_000L} micro")
      val results = new ArrayBuffer[String](topN)
      for (ns <- result.getNodes()) {
        val nodeId = ns.node
        val score  = ns.score
        results.addOne(s"score:$score;nodeId:$nodeId")
        // TODO: ???
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
