package com.evolution.natseffect.jetstream

import berlin.yuna.natsserver.config.{NatsConfig, NatsOptions}
import berlin.yuna.natsserver.logic.Nats as NatsServer
import cats.effect.{IO, Resource}

/** Boots an embedded real nats-server with JetStream enabled. Shared by the test suite and the loadtest module so the boot subtleties live
  * in one place.
  */
object EmbeddedNats {

  /** @param shutdownHook
    *   when true, the wrapper registers a JVM shutdown hook that stops the server even if resource finalizers never run (e.g. Ctrl-C on a
    *   plain-main app). Tests manage the lifecycle explicitly and keep it off.
    */
  def resource(port: Int, shutdownHook: Boolean = false): Resource[IO, NatsServer] =
    Resource.make(
      IO.blocking {
        new NatsServer(
          NatsOptions
            .natsBuilder()
            .port(port)
            // Bind to loopback so Nats.url() yields a connectable address; the default bind (0.0.0.0) is a wildcard
            // address that is not a reliable connect target.
            .config(NatsConfig.NET, "127.0.0.1")
            .jetStream(true)
            .autostart(true)
            .shutdownHook(shutdownHook)
        )
      }
    )(nats => IO.blocking(nats.close()))
}
