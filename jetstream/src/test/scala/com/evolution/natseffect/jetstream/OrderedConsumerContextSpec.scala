package com.evolution.natseffect.jetstream

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import com.evolution.natseffect.jetstream.Warmup.WarmupConsumerContextOps
import io.nats.client.api.{DeliverPolicy, OrderedConsumerConfiguration}
import io.nats.client.impl.NatsJetStreamMetaData
import weaver.GlobalRead

import scala.concurrent.duration.*

class OrderedConsumerContextSpec(global: GlobalRead) extends JetStreamSpec(global) {

  private def readMessage(queue: Queue[IO, JetStreamMessage[IO]]): Resource[IO, (NatsJetStreamMetaData, String)] =
    Resource.eval {
      queue.take.flatMap { msg =>
        msg.metaData.map(_ -> new String(msg.data.getOrElse(Array.empty[Byte])))
      }
    }

  testResource("ordered consumer with single subscription and warmup") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource

      // Pre-publish messages before consumer creation
      _ <- publisher.publish(subject, "message 1".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource
      _ <- publisher.publish(subject, "message 3".getBytes()).toResource

      sc <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedConsumer(
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

  testResource("ordered consumer warmup timeout") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource

      // Pre-publish multiple messages before consumer creation
      _ <- publisher.publish(subject, "message 1".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource
      _ <- publisher.publish(subject, "message 3".getBytes()).toResource

      sc <- js.streamContext(streamName).toResource

      occ <- sc
        .createOrderedConsumer(
          new OrderedConsumerConfiguration()
            .deliverPolicy(DeliverPolicy.All)
        )
        .toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      // Use consumeWithWarmup with slow processing and short timeout
      // Processing takes 200ms per message, but timeout is only 500ms
      // So we can process ~2 messages before timeout
      subscription <- occ.consumeWithWarmup(
        msg => IO.sleep(200.millis) >> queue.offer(msg),
        timeout = 500.millis
      )

      (_, data1) <- readMessage(queue)
      (_, data2) <- readMessage(queue)

      // Wait for warmup to complete (should time out)
      warmupResult <- subscription.warmupLatch.get.toResource

      // Messages should still be processed after timeout
      (_, data3) <- readMessage(queue)

    } yield
    // Warmup should have timed out
    matches(warmupResult) { case Warmup.Result.Timeout(_) => success } &&
      // But messages are still being processed
      expect.eql(data1, "message 1") &&
      expect.eql(data2, "message 2") &&
      expect.eql(data3, "message 3")
  }
}
