package com.github.haghard.vsearch

import akka.http.scaladsl.server.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import io.opentelemetry.api.metrics.{LongGauge, Meter}
import org.slf4j.Logger

import scala.collection.concurrent.TrieMap
import scala.util.*

trait HttpRouteSupport {

  val timeoutResponse =
    HttpResponse(StatusCodes.NetworkReadTimeout, entity = "Unable to serve response within time limit.")

  def aroundRequest[T](onRequest: RequestContext => Try[RouteResult] => Unit): Directive0 =
    (extractRequestContext & extractExecutionContext).tflatMap { tuple =>
      val onDone = onRequest(tuple._1)
      mapInnerRoute { inner =>
        withRequestTimeoutResponse { _ =>
          onDone(Success(Complete(timeoutResponse)))
          timeoutResponse
        } {
          inner.andThen { resultFuture =>
            resultFuture
              .map {
                case c @ Complete(response) =>
                  Complete(
                    response.mapEntity { entity =>
                      if (entity.isKnownEmpty) {
                        onDone(Success(c))
                        entity
                      } else
                        // On an empty entity, `transformDataBytes` unsets `isKnownEmpty`.
                        // Call onDone right away, since there's no significant amount of
                        // data to send, anyway.
                        entity.transformDataBytes(Flow[ByteString].watchTermination() { case (m, f) =>
                          f.map(_ => c)(tuple._2).onComplete(onDone)(tuple._2)
                          m
                        })
                    }
                  )
                case other =>
                  onDone(Success(other))
                  other
              }(tuple._2)
              .andThen { // skip this if you use akka.http.scaladsl.server.handleExceptions, put onDone there
                case Failure(ex) => onDone(Failure(ex))
              }(tuple._2)
          }
        }
      }
    }

  def logResponseTime(log: Logger, httpMeter: Meter, httpGauges: TrieMap[String, LongGauge])(
    ctx: RequestContext
  ): Try[RouteResult] => Unit = {
    val start = System.currentTimeMillis

    /*
      The request completes successfully, Success(Complete(response)) is passed to onDone
      The request is rejected (e.g. because of a non-matching inner route), then Success(Rejected(rejections)) is passed
      Producing the response body fails, and hence the request fails as well: Failure is passed to onDone
     */
    {
      case Success(Complete(resp)) =>
        val msLatency = System.currentTimeMillis - start

        val url      = ctx.request.uri.path.toString
        val segments = url.split('/')
        if (segments.length > 2) {
          val indexName  = segments(segments.length - 2)
          val httpOpName = segments(segments.length - 1)

          val gName = indexName + "-http-" + httpOpName
          val gauge = httpGauges.getOrElseUpdate(
            gName,
            httpMeter
              .gaugeBuilder(gName)
              .setDescription("description")
              .setUnit("1")
              .ofLongs()
              .build()
          )

          gauge.set(msLatency)
          log.info(s"""[${resp.status.intValue}] ${ctx.request.method.name} $url took:$msLatency ms""")
        }
      case Success(Rejected(_)) =>
      /*
          val msLatency = System.currentTimeMillis - start
          val url = ctx.request.uri.path.toString
          val params = ctx.request.uri.rawQueryString.fold("")(identity)
          log.error(s"""Rejected:${ctx.request.method.name} ${url}?${params} took:${msLatency} ms""")*/
      case Failure(ex) =>
        val msLatency = System.currentTimeMillis - start
        val url       = ctx.request.uri.path.toString
        val params    = ctx.request.uri.rawQueryString.fold("")(identity)
        log.error(s"""Failure:${ctx.request.method.name} ${url}?${params} took:${msLatency} ms""", ex)
    }
  }
}
