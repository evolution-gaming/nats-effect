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
      connection      <- Nats.connect(ctx.url())
      dispatcher      <- connection.createDispatcher()
      subject         <- randomSubject.toResource
      deliverDeferred <- Deferred[IO, Message].toResource
      _ <- dispatcher
        .subscribe(subject) { msg =>
          IO.sleep(1.second) *> deliverDeferred.complete(msg).void
        }
        .toResource
      pendingMessagesBefore <- dispatcher.pendingMessageCount.toResource
      pendingBytesBefore    <- dispatcher.pendingByteCount.toResource
      deliveredBefore       <- dispatcher.deliveredCount.toResource
      _                     <- connection.publish(subject, "test".getBytes).toResource

      _ <- {
        for {
          bytes    <- dispatcher.pendingByteCount
          messages <- dispatcher.pendingMessageCount
        } yield bytes > 0 && messages == 1
      }.iterateUntil(identity).timeout(5.second).toResource

      _ <- deliverDeferred.get.toResource

      pendingMessagesAfter <- dispatcher.pendingMessageCount.toResource
      pendingBytesAfter    <- dispatcher.pendingByteCount.toResource
      deliveredAfter       <- dispatcher.deliveredCount.toResource
    } yield expect(
      pendingMessagesBefore == 0L &&
        pendingBytesBefore == 0L &&
        pendingMessagesAfter == 0L &&
        pendingBytesAfter == 0L &&
        deliveredBefore == 0L &&
        deliveredAfter == 1L
    )
  }

}
