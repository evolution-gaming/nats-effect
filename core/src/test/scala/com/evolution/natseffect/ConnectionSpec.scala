package com.evolution.natseffect

import cats.effect.implicits.genTemporalOps
import cats.effect.{Deferred, IO}
import cats.implicits.{catsSyntaxApplicativeError, toFunctorOps}
import io.nats.client.Connection.Status
import io.nats.client.ConnectionListener.Events
import weaver.GlobalRead

import java.io.IOException
import java.net.InetAddress
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ConnectionSpec(global: GlobalRead) extends NatsSpec(global) {

  testResource("return correct settings") { ctx =>
    for {
      connection  <- Nats.connect(ctx.url())
      serverInfo  <- connection.serverInfo.toResource
      inetAddress <- connection.clientInetAddress.toResource
      status      <- connection.status.toResource
    } yield expect(
      serverInfo.map(_.getPort).contains(ctx.port()) &&
        status == Status.CONNECTED &&
        (inetAddress.contains(InetAddress.getLoopbackAddress) ||
          inetAddress.contains(InetAddress.getLocalHost))
    )
  }

  testResource("fail on the first connect") { _ =>
    for {
      options <- IO(Options[IO]().withReconnectOnConnect(false).withNatsServerUris(Seq("nats://localhost:1111"))).toResource
      failed <- Nats.connect(options).as(false).recover {
        case _: IOException => true
      }
    } yield expect(failed)
  }

  testResource("keep trying to reconnect") { _ =>
    for {
      options <- IO(
        Options[IO]().withReconnectOnConnect(true).withMaxReconnects(2).withNatsServerUris(Seq("nats://localhost:1111"))
      ).toResource
      failed <- Nats.connect(options).timeout(1.second).as(false).recover {
        case _: TimeoutException => true
      }
    } yield expect(failed)
  }

  testResource("ConnectionListener receives events") { ctx =>
    for {
      deferred <- Deferred[IO, Events].toResource
      listener <- IO(
        new ConnectionListener[IO] {
          override def connectionEvent(conn: Connection[IO], `type`: Events, time: Long, uriDetails: String): IO[Unit] =
            deferred.complete(`type`).void
        }
      ).toResource
      options       <- IO(Options[IO]().withNatsServerUris(Seq(ctx.url())).withConnectionListener(Some(listener))).toResource
      _             <- Nats.connect(options)
      receivedEvent <- deferred.get.timeout(5.second).toResource
    } yield expect(receivedEvent == Events.CONNECTED)
  }

}
