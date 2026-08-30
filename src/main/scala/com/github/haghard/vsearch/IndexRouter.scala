package com.github.haghard.vsearch

import SearchIndex.Protocol
import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import io.opentelemetry.api.metrics.*

object IndexRouter {

  def apply(handle: Observability.Handle, embedder: Embedder, dimensions: Int): Behavior[SearchIndex.Protocol] =
    Behaviors.setup { ctx =>
      val gauge = handle.telemetry
        .getMeter("index-counter")
        .gaugeBuilder("index-counter")
        .setUnit("1")
        .ofLongs()
        .build()
      active(0L, gauge, handle, embedder, dimensions)(ctx)
    }

  def active(
    indCounter: Long,
    gauge: LongGauge,
    handle: Observability.Handle,
    embedder: Embedder,
    dimensions: Int
  )(implicit ctx: ActorContext[?]): Behavior[SearchIndex.Protocol] =
    Behaviors.receiveMessage { case cmd: Protocol =>
      val (graphIndex, indexCounter) =
        ctx.child(cmd.index) match {
          case Some(ind) =>
            (ind.unsafeUpcast[SearchIndex.Protocol], indCounter)
          case None =>
            val next = indCounter + 1

            gauge.set(next)
            (
              ctx.spawn(
                SearchIndex(handle.telemetry.getMeter(cmd.index), embedder, dimensions),
                cmd.index,
                Props.empty.withDispatcherFromConfig("akka.index-dispatcher")
              ),
              next
            )
        }

      graphIndex.tell(cmd)
      active(indexCounter, gauge, handle, embedder, dimensions)(ctx)
    }
}
