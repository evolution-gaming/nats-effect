package com.evolution.natseffect.jetstream

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import com.evolution.natseffect.jetstream.Warmup.WarmupConsumerContextOps
import io.nats.client.ConsumeOptions
import io.nats.client.api.{DeliverPolicy, OrderedConsumerConfiguration}
import io.nats.client.impl.NatsJetStreamMetaData
import weaver.GlobalRead

import scala.concurrent.duration.*

class PacedOrderedConsumerContextSpec(global: GlobalRead) extends JetStreamSpec(global) {

  private def readMessage(queue: Queue[IO, JetStreamMessage[IO]]): Resource[IO, (NatsJetStreamMetaData, String)] =
    Resource.eval {
      queue.take.flatMap { msg =>
        msg.metaData.map(_ -> new String(msg.data.getOrElse(Array.empty[Byte])))
      }
    }

  testResource("paced ordered consumer with single subscription and warmup") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource

      // Pre-publish messages before consumer creation
      _ <- publisher.publish(subject, "message 1".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource
      _ <- publisher.publish(subject, "message 3".getBytes()).toResource

      sc <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All)
        )
        .toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      // Use consumeWithWarmup to wait for pre-published messages
      subscription <- occ.consumeWithWarmup(
        msg => queue.offer(msg),
        timeout = 5.seconds
      )

      // Second subscription should fail, it is not allowed for ordered consumers
      secondSubscription <- occ
        .consume(msg => queue.offer(msg))
        .attempt

      // Wait for warmup to complete
      warmupResult <- subscription.warmupLatch.get.toResource

      // Fetch all pre-published messages
      (meta1, data1) <- readMessage(queue)
      (meta2, data2) <- readMessage(queue)
      (meta3, data3) <- readMessage(queue)

      // Publish new messages after warmup
      _ <- publisher.publish(subject, "message 4".getBytes()).toResource

      (meta4, data4) <- readMessage(queue)

    } yield expect(secondSubscription.isLeft) &&
      matches(warmupResult) { case Warmup.Result.Success(_) => success } &&
      // Message data assertions
      expect.eql(data1, "message 1") &&
      expect.eql(data2, "message 2") &&
      expect.eql(data3, "message 3") &&
      expect.eql(data4, "message 4") &&
      // Sequence assertions
      expect.eql(meta1.streamSequence(), 1L) &&
      expect.eql(meta2.streamSequence(), 2L) &&
      expect.eql(meta3.streamSequence(), 3L) &&
      expect.eql(meta4.streamSequence(), 4L) &&
      expect.eql(meta1.consumerSequence(), 1L) &&
      expect.eql(meta2.consumerSequence(), 2L) &&
      expect.eql(meta3.consumerSequence(), 3L) &&
      expect.eql(meta4.consumerSequence(), 4L)
  }

  // The engine owns gap detection and recovery: when the server-side consumer disappears, the loop
  // re-creates it and resumes after the last delivered stream sequence. The deletion makes the
  // connection error listener log a SEVERE line - that is expected here.
  testResource("paced ordered consumer recovers after its consumer is deleted") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource
      sc        <- js.streamContext(streamName).toResource
      listener  <- CountingPacedListener.make.toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All),
          listener
        )
        .toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      // Short pull window so the engine notices the missing consumer quickly
      subscription <- occ.consume(
        msg => queue.offer(msg),
        ConsumeOptions.builder().expiresIn(1000).build()
      )

      _              <- publisher.publish(subject, "before".getBytes()).toResource
      (meta1, data1) <- readMessage(queue)

      consumerName <- subscription.getConsumerName.toResource
      deleted      <- sc.deleteConsumer(consumerName).toResource

      _              <- publisher.publish(subject, "after".getBytes()).toResource
      (meta2, data2) <- readMessage(queue)

      recreatedName <- subscription.getConsumerName.toResource

      resubscribes   <- listener.resubscribes.toResource
      resubscribings <- listener.resubscribings.toResource
      failures       <- listener.failures.toResource

    } yield expect(deleted) &&
      expect.eql(data1, "before") &&
      expect.eql(data2, "after") &&
      expect.eql(meta1.streamSequence(), 1L) &&
      expect.eql(meta2.streamSequence(), 2L) &&
      expect(recreatedName != consumerName) &&
      expect(resubscribes >= 1) &&
      // The recovery is observable either as a 409-status failure or as a liveness-probe
      // resubscribing signal, depending on which the server surfaces first
      expect(failures + resubscribings >= 1)
  }

  // Parity with the jnats ordered context: sequential consume calls on one context continue after
  // the last delivered stream sequence instead of replaying from the original deliver policy
  testResource("sequential paced consumes continue from the last delivered sequence") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource
      sc        <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All)
        )
        .toResource

      _ <- publisher.publish(subject, "message 1".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      firstTwo <- Resource.eval {
        occ.consume(msg => queue.offer(msg)).use { _ =>
          readMessage(queue).use(m1 => readMessage(queue).use(m2 => IO.pure(List(m1._2, m2._2))))
        }
      }

      _ <- publisher.publish(subject, "message 3".getBytes()).toResource

      _              <- occ.consume(msg => queue.offer(msg))
      (meta3, data3) <- readMessage(queue)

    } yield expect.eql(firstTwo, List("message 1", "message 2")) &&
      expect.eql(data3, "message 3") &&
      expect.eql(meta3.streamSequence(), 3L)
  }

  testResource("paced subscription stop halts message flow") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource
      sc        <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All)
        )
        .toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      // Short pull window so the loop notices the stop promptly even when idle
      subscription <- occ.consume(
        msg => queue.offer(msg),
        ConsumeOptions.builder().expiresIn(1000).build()
      )

      _          <- publisher.publish(subject, "message 1".getBytes()).toResource
      (_, data1) <- readMessage(queue)

      _       <- subscription.stop.toResource
      stopped <- subscription.isStopped.toResource
      _       <- awaitFinished(subscription).toResource

      // Published after the loop exited: must never be delivered
      _        <- publisher.publish(subject, "message 2".getBytes()).toResource
      _        <- IO.sleep(300.millis).toResource
      leftover <- queue.tryTake.toResource

    } yield expect.eql(data1, "message 1") &&
      expect(stopped) &&
      expect(leftover.isEmpty)
  }

  // Releasing the subscription must interrupt a wait parked inside the (default, 30s) pull window
  testResource("paced subscription release is prompt while idle") { ctx =>
    for {
      (js, streamName, _) <- setupStream(ctx)

      sc <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All)
        )
        .toResource

      releaseTime <- Resource.eval {
        occ.consume(_ => IO.unit).allocated.flatMap {
          case (_, release) =>
            // Let the loop park inside nextMessage on the empty stream first
            IO.sleep(500.millis) *> release.timed.map(_._1)
        }
      }

    } yield expect(releaseTime < 3.seconds)
  }

  // A failing handler must not tear down the subscription; the failure is surfaced via the
  // listener (and the connection error listener, which logs a SEVERE line here - expected)
  testResource("paced consumer continues after a handler failure") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource
      sc        <- js.streamContext(streamName).toResource
      listener  <- CountingPacedListener.make.toResource

      occ <- sc
        .createOrderedPacedConsumer(
          new OrderedConsumerConfiguration().deliverPolicy(DeliverPolicy.All),
          listener
        )
        .toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      _ <- occ.consume { msg =>
        val data = new String(msg.data.getOrElse(Array.empty[Byte]))
        if (data == "boom") IO.raiseError(new RuntimeException("handler failure"))
        else queue.offer(msg)
      }

      _ <- publisher.publish(subject, "boom".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource

      (meta2, data2)  <- readMessage(queue)
      handlerFailures <- listener.handlerFailures.toResource
      resubscribes    <- listener.resubscribes.toResource
      processed       <- listener.processed.toResource

    } yield expect.eql(data2, "message 2") &&
      expect.eql(meta2.streamSequence(), 2L) &&
      expect.eql(handlerFailures, 1) &&
      expect.eql(resubscribes, 0) &&
      expect.eql(processed, 1)
  }

  private def awaitFinished(subscription: MessageSubscription[IO]): IO[Unit] = {
    def poll: IO[Unit] =
      subscription.isFinished.flatMap {
        case true  => IO.unit
        case false => IO.sleep(100.millis) >> poll
      }
    poll.timeout(5.seconds)
  }
}
