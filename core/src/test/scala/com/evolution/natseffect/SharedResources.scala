package com.evolution.natseffect

import berlin.yuna.natsserver.logic.Nats
import cats.effect.{IO, Resource}
import weaver.{GlobalRead, GlobalResource, GlobalWrite}

object SharedResources extends GlobalResource {

  private val resource = Resource.make(IO.delay(new Nats(4223)))(n => IO.delay(n.close()))

  override def sharedResources(global: GlobalWrite): Resource[IO, Unit] =
    for {
      nats <- resource
      _    <- global.putR(nats)
    } yield ()

  def get(global: GlobalRead): Resource[IO, Nats] =
    global.getR[Nats]().flatMap {
      case None       => resource
      case Some(nats) => IO(nats).toResource
    }
}
