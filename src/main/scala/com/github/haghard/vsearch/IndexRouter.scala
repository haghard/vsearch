package com.github.haghard.vsearch

import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.actor.typed.scaladsl.Behaviors
import HNSWIndex.Protocol

object IndexRouter {

  def apply(embedder: Embedder, dimensions: Int): Behavior[HNSWIndex.Protocol] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessage { case cmd: Protocol =>
        val graphIndex =
          ctx
            .child(cmd.index)
            .getOrElse {
              ctx.spawn(
                HNSWIndex(embedder, dimensions),
                cmd.index,
                Props.empty.withDispatcherFromConfig("akka.index-dispatcher")
              )
            }
            .asInstanceOf[ActorRef[HNSWIndex.Protocol]]

        graphIndex.tell(cmd)
        Behaviors.same
      }
    }
}
