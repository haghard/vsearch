package com.github.haghard.vsearch

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.*
import akka.http.scaladsl.model.StatusCodes.*
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.model.ContentTypes.*
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.Source
import com.opencsv.CSVReader
import io.opentelemetry.api.metrics.LongGauge

import java.io.{BufferedReader, FileReader}
import java.nio.file.Paths
import scala.concurrent.Future
import scala.jdk.CollectionConverters.IteratorHasAsScala
import wvlet.airframe.*

import scala.collection.concurrent.TrieMap

trait Routes extends HttpRouteSupport {

  def httpRoutes(handle: Observability.Handle, graphIndexRouter: ActorRef[SearchIndex.Protocol])(implicit
    system: ActorSystem[?]
  ) = {
    implicit val sch              = system.scheduler
    implicit val timeout: Timeout = Timeout(3.seconds)

    // extractLog { implicit log => extractExecutionContext { implicit ec =>

    val indexName   = "reviews"
    val reviewsFile = Paths.get(s"./${indexName}.csv")
    val reader      = new CSVReader(new BufferedReader(new FileReader(reviewsFile.toFile)))
    reader.readNext() // drop header

    val graphIndexSink =
      akka.stream.typed.scaladsl.ActorSink.actorRefWithBackpressure(
        ref = graphIndexRouter,
        SearchIndex.Next(indexName, ulid.ULID.newULID.toString, _, _),
        SearchIndex.Init(indexName, ulid.ULID.newULID.toString, _),
        SearchIndex.Ack,
        SearchIndex.Completed(indexName, ulid.ULID.newULID.toString),
        SearchIndex.Failed(indexName, ulid.ULID.newULID.toString, _)
      )

    Source
      .fromIterator(() => reader.iterator().asScala)
      .take(1_024)
      .grouped(16)
      .map(lines => SearchIndex.PutBatch(indexName, ulid.ULID.newULID.toString, lines.map(_(9))))
      .to(graphIndexSink)
      .run()

    val httpMeter  = handle.telemetry.getMeter("http-root")
    val httpGauges = TrieMap.empty[String, LongGauge]

    aroundRequest(logResponseTime(system.log, httpMeter, httpGauges)) {
      pathPrefix("index") {
        get {
          path(Segment / "search") { indexName =>
            parameters(Symbol("q").as[String], Symbol("limit") ? 7) { (q, limit) =>
              val reqId = ulid.ULID.newULID.toString
              val f     = graphIndexRouter.ask[Option[String]](SearchIndex.Get(indexName, reqId, limit, q, _))
              // HttpResponse(entity = Strict(`text/html(UTF-8)`, ByteString(q)))
              // onSuccess(f) { reqOpt => complete(reqOpt) }
              onComplete(f) {
                case Success(Some(results)) =>
                  complete(HttpResponse(entity = Strict(`text/html(UTF-8)`, ByteString(results))))
                // complete(OK -> s"""{"results":"${results}"}""")
                case Success(None) =>
                  complete(NotFound)
                case Failure(err) =>
                  complete(InternalServerError -> err)
              }
            }
          }
        } ~ post {
          path(Segment / "save") { indexName =>
            parameters(Symbol("text").as[String]) { text =>
              val reqId = ulid.ULID.newULID.toString
              val f     = graphIndexRouter.ask[SearchIndex.Ack](SearchIndex.Put(indexName, reqId, text, _))
              onComplete(f) {
                case Success(_) =>
                  complete(HttpResponse(entity = Strict(`text/html(UTF-8)`, ByteString(reqId))))
                // complete(OK)
                case Failure(err) => complete(InternalServerError -> err)
              }
            }
          }
        }
      }
    } ~ get {
      path("jcmd") {
        val f = Future(println(HeapUtils.logJcmd()))(
          system.dispatchers.lookup(DispatcherSelector.fromConfig("akka.jcmd-dispatcher"))
        )
        onSuccess(f)(complete(OK))
      }
    }
  }
}
