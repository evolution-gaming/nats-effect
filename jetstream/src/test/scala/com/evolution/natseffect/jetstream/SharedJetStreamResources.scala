package com.evolution.natseffect.jetstream

import berlin.yuna.natsserver.logic.Nats
import cats.effect.{IO, Resource}
import weaver.{GlobalRead, GlobalResource, GlobalWrite}

object SharedJetStreamResources extends GlobalResource {

  private val resource = EmbeddedNats.resource(port = 4224)

  override def sharedResources(global: GlobalWrite): Resource[IO, Unit] =
    for {
      nats <- resource
      _    <- global.putR(nats, Some("js"))
    } yield ()

  def get(global: GlobalRead): Resource[IO, Nats] =
    global.getR[Nats](Some("js")).flatMap {
      case None       => resource
      case Some(nats) => IO(nats).toResource
    }
}
