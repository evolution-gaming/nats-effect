package com.evolution.natseffect

import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher as CEDispatcher
import com.evolution.natseffect.impl.{CEMessageHandler, JavaWrapper}
import io.nats.client.Connection as JConnection
import weaver.GlobalRead

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.DurationInt

// Test for #14
class DispatcherReleaseRaceSpec(global: GlobalRead) extends NatsSpec(global) {

  testResource("pending counters are released when a message is pushed after the CE dispatcher is closed") { ctx =>
    for {
      connection <- Nats.connect[IO](ctx.url())
      jConnection = connection.asInstanceOf[JavaWrapper[JConnection]].asJava
      subject    <- randomSubject.toResource

      closedDispatcher <- CEDispatcher
        .sequential[IO]
        .allocated
        .flatMap { case (dispatcher, release) => release.as(dispatcher) }
        .toResource

      throwsWhenClosed <- IO(
        try { closedDispatcher.unsafeToFuture(IO.unit); false }
        catch { case _: IllegalStateException => true }
      ).toResource

      effectRan    = new AtomicBoolean(false)
      handler      = CEMessageHandler[IO](closedDispatcher, _ => IO(effectRan.set(true)))
      jDispatcher <- Resource.make(IO(jConnection.createDispatcher()))(d => IO(jConnection.closeDispatcher(d)))
      _            = jDispatcher.subscribe(subject, handler)

      _ <- connection.publish(subject, "test".getBytes).toResource

      _ <- IO(jDispatcher.getDeliveredCount)
        .flatTap(_ => IO.sleep(50.millis))
        .iterateUntil(_ == 1L)
        .timeout(5.seconds)
        .toResource
      _ <- IO(jDispatcher.getPendingMessageCount)
        .flatTap(_ => IO.sleep(50.millis))
        .iterateUntil(_ == 0L)
        .timeout(5.seconds)
        .toResource

      pendingMessages <- IO(jDispatcher.getPendingMessageCount).toResource
      pendingBytes    <- IO(jDispatcher.getPendingByteCount).toResource
    } yield expect.all(
      clue(throwsWhenClosed),
      !clue(effectRan.get()),
      clue(pendingMessages) == 0L,
      clue(pendingBytes) == 0L
    )
  }

}
