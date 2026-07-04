package com.evolution.natseffect

import berlin.yuna.natsserver.config.{NatsConfig, NatsOptions}
import berlin.yuna.natsserver.logic.Nats
import cats.effect.{IO, Resource}
import weaver.{GlobalRead, GlobalResource, GlobalWrite}

object SharedResources extends GlobalResource {

  private val resource = Resource.make(
    IO.delay(
      new Nats(
        NatsOptions
          .natsBuilder()
          .port(4223)
          // Bind to loopback so Nats.url() yields a connectable address; the default bind (0.0.0.0) is a wildcard
          // address that is not a reliable connect target.
          .config(NatsConfig.NET, "127.0.0.1")
          .autostart(true)
          .shutdownHook(false)
      )
    )
  )(n => IO.delay(n.close()))

  override def sharedResources(global: GlobalWrite): Resource[IO, Unit] =
    for {
      nats <- resource
      _    <- global.putR(nats, Some("core"))
    } yield ()

  def get(global: GlobalRead): Resource[IO, Nats] =
    global.getR[Nats](Some("core")).flatMap {
      case None       => resource
      case Some(nats) => IO(nats).toResource
    }
}
