package com.evolution.natseffect

import cats.effect.implicits.genTemporalOps
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{JavaWrapper, JConnection}
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
      failed  <- Nats.connect(options).as(false).recover {
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

  testResource("tolerate dispatcher release after the connection is already closed") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      // Close the underlying connection while the dispatcher is still allocated, then release it:
      // the finalizer must treat "Connection is Closed" as a no-op instead of failing the chain
      released <- connection
        .createDispatcher()
        .allocated
        .flatMap {
          case (_, release) =>
            IO(connection.asInstanceOf[JavaWrapper[JConnection]].asJava.close()).flatMap(_ => release.attempt)
        }
        .toResource
    } yield expect(released.isRight)
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

  testResource("connect asynchronously") { ctx =>
    for {
      deferred <- Deferred[IO, Events].toResource
      listener  = new ConnectionListener[IO] {
        override def connectionEvent(conn: Connection[IO], `type`: Events, time: Long, uriDetails: String): IO[Unit] =
          deferred.complete(`type`).void
      }
      options <- IO(
        Options[IO]().withConnectAsynchronously(true).withNatsServerUris(Seq(ctx.url())).withConnectionListener(Some(listener))
      ).toResource
      connection    <- Nats.connect(options)
      receivedEvent <- deferred.get.timeout(5.seconds).toResource
      status        <- connection.status.toResource
      _             <- connection.publish("subject", Array.emptyByteArray).toResource
    } yield expect(receivedEvent == Events.CONNECTED && status == Status.CONNECTED)
  }

  testResource("wait for the asynchronous connection in operations") { ctx =>
    for {
      options    <- IO(Options[IO]().withConnectAsynchronously(true).withNatsServerUris(Seq(ctx.url()))).toResource
      connection <- Nats.connect(options)
      // issued right away, before the connection is likely to be established
      _      <- connection.publish("subject", Array.emptyByteArray).toResource
      status <- connection.status.toResource
    } yield expect(status == Status.CONNECTED)
  }

  testResource("return immediately and fail operations if not connected asynchronously within connectionTimeout") { _ =>
    for {
      options <- IO(
        Options[IO]()
          .withConnectAsynchronously(true)
          .withConnectionTimeout(100.millis)
          .withNatsServerUris(Seq("nats://localhost:1111"))
      ).toResource
      // acquisition and release must not wait for the connection
      result <- Nats
        .connect(options)
        .use { connection =>
          (
            connection.status.attempt,
            connection.publish("subject", Array.emptyByteArray).attempt,
            connection.createDispatcher().use_.attempt
          ).tupled
        }
        .timeout(5.seconds)
        .toResource
      (status, publish, dispatcher) = result
    } yield expect.all(
      status.left.exists(_.isInstanceOf[Connection.NotEstablishedException]),
      publish.left.exists(_.isInstanceOf[Connection.NotEstablishedException]),
      dispatcher.left.exists(_.isInstanceOf[Connection.NotEstablishedException])
    )
  }

  testResource("keep connecting asynchronously until the server is available") { _ =>
    val port = 4224
    for {
      options <- IO(
        Options[IO]()
          .withConnectAsynchronously(true)
          .withReconnectWait(100.millis)
          .withConnectionTimeout(100.millis)
          .withNatsServerUris(Seq(s"nats://127.0.0.1:$port"))
      ).toResource
      connection <- Nats.connect(options)
      // let a few passes fail before the server is up
      before <- (IO.sleep(500.millis) >> connection.status).attempt.toResource
      _      <- Resource.make(
        IO.blocking(
          new berlin.yuna.natsserver.logic.Nats(
            berlin.yuna.natsserver.config.NatsOptions
              .natsBuilder()
              .port(port)
              .config(berlin.yuna.natsserver.config.NatsConfig.NET, "127.0.0.1")
              .autostart(true)
              .shutdownHook(false)
          )
        )
      )(server => IO.blocking(server.close()))
      after <- connection.status.attempt.iterateUntil(_.isRight).timeout(10.seconds).toResource
    } yield expect.all(
      before.left.exists(_.isInstanceOf[Connection.NotEstablishedException]),
      after == Right(Status.CONNECTED)
    )
  }

}
