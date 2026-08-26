package com.github.haghard.vsearch

import akka.Done
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

import java.io.{BufferedReader, FileReader}
import java.nio.file.Paths
import scala.concurrent.Future
import scala.jdk.CollectionConverters.IteratorHasAsScala
import wvlet.airframe.*

trait Routes {

  def httpRoutes(graphIndexRouter: ActorRef[HNSWIndex.Protocol])(implicit
    system: ActorSystem[?]
  ) = {
    implicit val sch              = system.scheduler
    implicit val timeout: Timeout = Timeout(3.seconds)

    // extractLog { implicit log => extractExecutionContext { implicit ec =>

    val reviewsFile = Paths.get("./reviews.csv")
    val reader      = new CSVReader(new BufferedReader(new FileReader(reviewsFile.toFile)))
    reader.readNext()

    Source
      .fromIterator(() => reader.iterator().asScala)
      .take(10_000)
      .grouped(32)
      .mapAsync(2) { lines =>
        val reqId = ulid.ULID.newULID.toString
        graphIndexRouter.ask[Done](HNSWIndex.Protocol.PutN("reviews", reqId, lines.filter(_ ne null).map(_(9)), _))
      }
      .run()
      .onComplete(_ => println("reviews.csv injected"))(system.executionContext)

    // http :8080/index/reviews/search"?q=awesome coffee"

    get {
      path("jcmd") {
        val f = Future(println(HeapUtils.logJcmd()))(
          system.dispatchers.lookup(DispatcherSelector.fromConfig("akka.jcmd-dispatcher"))
        )
        onSuccess(f)(complete(OK))
      }
    } ~ pathPrefix("index") {
      get {
        path(Segment / "search") { indexName =>
          parameters(Symbol("q").as[String], Symbol("limit") ? 7) { (q, limit) =>
            val reqId = ulid.ULID.newULID.toString
            val f     = graphIndexRouter.ask[Option[String]](HNSWIndex.Protocol.Get(indexName, reqId, limit, q, _))
            // HttpResponse(entity = Strict(`text/html(UTF-8)`, ByteString(q)))
            // onSuccess(f) { reqOpt => complete(reqOpt) }
            onComplete(f) {
              case Success(Some(results)) =>
                complete(HttpResponse(entity = Strict(`text/html(UTF-8)`, ByteString(results))))
              case Success(None) => complete(NotFound)
              case Failure(err)  => complete(InternalServerError -> err)
            }

          }
        }
      } ~ post {
        // http POST :8080/index/reviews/save"?text=I am writing to inquire about student accommodation at the Shires College Hall of Residence at Cardiff University"
        path(Segment / "save") { indexName =>
          parameters(Symbol("text").as[String]) { text =>
            val reqId = ulid.ULID.newULID.toString
            val f     = graphIndexRouter.ask[Done](HNSWIndex.Protocol.Put(indexName, reqId, text, _))
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
  }
}
