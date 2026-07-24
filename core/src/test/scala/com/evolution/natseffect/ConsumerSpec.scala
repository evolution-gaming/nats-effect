package com.evolution.natseffect

import cats.effect.{Deferred, IO}
import weaver.GlobalRead

import scala.concurrent.duration.DurationInt
import scala.util.Random

class ConsumerSpec(global: GlobalRead) extends NatsSpec(global) {

  testResource("change pending limits") { ctx =>
    for {
      connection          <- Nats.connect(ctx.url())
      dispatcher          <- connection.createDispatcher()
      expectedMaxMessages <- IO(Random.nextLong(100000) + 1).toResource
      expectedMaxBytes    <- IO(Random.nextLong(100000) + 1).toResource
      _                   <- dispatcher.setPendingLimits(expectedMaxMessages, expectedMaxBytes).toResource
      maxMessages         <- dispatcher.pendingMessageLimit.toResource
      maxBytes            <- dispatcher.pendingByteLimit.toResource
    } yield expect(maxMessages == expectedMaxMessages && maxBytes == expectedMaxBytes)
  }

  testResource("increase pending messages and pending bytes when a message is read but not yet processed") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject    <- randomSubject.toResource
      started    <- Deferred[IO, Unit].toResource
      release    <- Deferred[IO, Unit].toResource
      _          <- dispatcher.subscribe(subject)(_ => started.complete(()) *> release.get).toResource

      pendingMessagesBefore <- dispatcher.pendingMessageCount.toResource
      pendingBytesBefore    <- dispatcher.pendingByteCount.toResource
      deliveredBefore       <- dispatcher.deliveredCount.toResource

      _ <- connection.publish(subject, "test".getBytes).toResource
      _ <- started.get.timeout(5.seconds).toResource

      pendingMessagesDuring <- dispatcher.pendingMessageCount.toResource
      pendingBytesDuring    <- dispatcher.pendingByteCount.toResource
      deliveredDuring       <- dispatcher.deliveredCount.toResource

      _ <- release.complete(()).toResource

      _ <- {
        for {
          bytes    <- dispatcher.pendingByteCount
          messages <- dispatcher.pendingMessageCount
        } yield bytes == 0L && messages == 0L
      }.iterateUntil(identity).timeout(5.seconds).toResource
    } yield expect(
      pendingMessagesBefore == 0L &&
        pendingBytesBefore == 0L &&
        deliveredBefore == 0L &&
        pendingMessagesDuring == 1L &&
        pendingBytesDuring > 0L &&
        deliveredDuring == 1L
    )
  }

}
