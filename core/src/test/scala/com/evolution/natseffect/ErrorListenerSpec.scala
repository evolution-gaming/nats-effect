package com.evolution.natseffect

import cats.effect.{Deferred, IO, Ref}
import weaver.GlobalRead

import scala.concurrent.duration.DurationInt

class ErrorListenerSpec(global: GlobalRead) extends NatsSpec(global) {

  testResource("detect slow consumer") { ctx =>
    for {
      detected <- Ref.of(false).toResource
      errorListener <- IO(
        new ErrorListener[IO] {
          override def errorOccurred(conn: Connection[IO], error: String): IO[Unit]                 = IO.unit
          override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit]            = IO.unit
          override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] = detected.set(true)
          override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit]               = IO.unit
          override def socketWriteTimeout(conn: Connection[IO]): IO[Unit]                           = IO.unit
        }
      ).toResource
      options           <- IO(Options[IO]().withNatsServerUris(Seq(ctx.url())).withErrorListener(Some(errorListener))).toResource
      connection        <- Nats.connect(options)
      dispatcher        <- connection.createDispatcher()
      _                 <- dispatcher.setPendingLimits(10L, 1024L).toResource
      subject           <- randomSubject.toResource
      deferred          <- Deferred[IO, Message].toResource
      _                 <- dispatcher.subscribe(subject)(msg => IO.sleep(1.second) *> deferred.complete(msg).void).toResource
      droppedBefore     <- dispatcher.droppedCount.toResource
      _                 <- connection.publish(subject, Array.empty).untilM_(detected.get).timeout(10.seconds).toResource
      _                 <- deferred.get.toResource
      droppedAfter      <- dispatcher.droppedCount.toResource
      _                 <- dispatcher.clearDroppedCount().toResource
      droppedAfterClear <- dispatcher.droppedCount.toResource
    } yield expect(droppedBefore == 0L && droppedAfter > 0L && droppedAfterClear == 0L)
  }

  testResource("detect discarded message") { ctx =>
    for {
      failedWith <- Ref.of[IO, Option[Message]](None).toResource
      errorListener <- IO(
        new ErrorListener[IO] {
          override def errorOccurred(conn: Connection[IO], error: String): IO[Unit]                 = IO.unit
          override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit]            = IO.unit
          override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] = IO.unit
          override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit]               = failedWith.set(Some(msg))
          override def socketWriteTimeout(conn: Connection[IO]): IO[Unit]                           = IO.unit
        }
      ).toResource
      options <- IO(
        Options[IO]().withNatsServerUris(Seq(ctx.url())).withErrorListener(Some(errorListener)).withMaxMessagesInOutgoingQueue(10)
      ).toResource
      connection <- Nats.connect(options)
      subject    <- randomSubject.toResource
      _ <- connection
        .publish(subject, "message".getBytes)
        .untilM_(failedWith.get.map(_.nonEmpty))
        .timeout(10.seconds)
        .toResource

      failedMessage <- failedWith.get.toResource
    } yield expect(failedMessage.map(_.subject).contains(subject) && failedMessage.flatMap(_.data.map(new String(_))).contains("message"))
  }

  testResource("detect thrown exception") { ctx =>
    for {
      deferred <- Deferred[IO, Throwable].toResource
      errorListener <- IO(
        new ErrorListener[IO] {
          override def errorOccurred(conn: Connection[IO], error: String): IO[Unit]                 = IO.unit
          override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit]            = deferred.complete(exp).void
          override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] = IO.unit
          override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit]               = IO.unit
          override def socketWriteTimeout(conn: Connection[IO]): IO[Unit]                           = IO.unit
        }
      ).toResource
      options <- IO(
        Options[IO]().withNatsServerUris(Seq(ctx.url())).withErrorListener(Some(errorListener))
      ).toResource
      connection      <- Nats.connect(options)
      dispatcher      <- connection.createDispatcher()
      subject         <- randomSubject.toResource
      exceptionToThrow = new RuntimeException("oops")
      _               <- dispatcher.subscribe(subject)(_ => IO.raiseError(exceptionToThrow)).toResource
      _               <- connection.publish(subject, Array.empty).toResource
      thrownException <- deferred.get.timeout(5.seconds).toResource
    } yield expect(thrownException == exceptionToThrow)
  }

}
