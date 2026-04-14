package com.evolution.natseffect.jetstream

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import io.nats.client.api.{AckPolicy, ConsumerConfiguration}
import weaver.GlobalRead

import java.time.Duration

class ConsumerContextSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("durable consumer lifecycle with ACK and redelivery") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)
      sc                        <- js.streamContext(streamName).toResource
      consumerName              <- randomConsumerName.toResource

      cc <- Resource.make(
        sc
          .createOrUpdateConsumer(
            ConsumerConfiguration
              .builder()
              .durable(consumerName)
              .ackPolicy(AckPolicy.Explicit)
              .ackWait(Duration.ofMillis(500))
              .build()
          )
      )(_ => sc.deleteConsumer(consumerName).void)

      publisher <- js.jetStreamPublisher().toResource

      // Test consumer info
      retrievedName <- cc.getConsumerName.toResource
      consumerInfo  <- cc.getConsumerInfo.toResource

      // Publish test messages
      _ <- publisher.publish(subject, "message 1".getBytes()).toResource
      _ <- publisher.publish(subject, "message 2".getBytes()).toResource
      _ <- publisher.publish(subject, "message 3".getBytes()).toResource
      _ <- publisher.publish(subject, "message to redeliver".getBytes()).toResource

      queue <- Queue.bounded[IO, JetStreamMessage[IO]](10).toResource

      _ <- cc.consume(msg => queue.offer(msg))

      // Fetch and acknowledge first three messages
      msg1  <- queue.take.toResource
      meta1 <- msg1.metaData.toResource
      _     <- msg1.ack.toResource
      data1  = new String(msg1.data.getOrElse(Array.empty[Byte]))

      msg2  <- queue.take.toResource
      meta2 <- msg2.metaData.toResource
      _     <- msg2.ack.toResource
      data2  = new String(msg2.data.getOrElse(Array.empty[Byte]))

      msg3  <- queue.take.toResource
      meta3 <- msg3.metaData.toResource
      _     <- msg3.ack.toResource
      data3  = new String(msg3.data.getOrElse(Array.empty[Byte]))

      // Ignore the fourth message to test redelivery
      msg4  <- queue.take.toResource
      meta4 <- msg4.metaData.toResource
      data4  = new String(msg4.data.getOrElse(Array.empty[Byte]))

      // Receive redelivered message
      msg5  <- queue.take.toResource
      meta5 <- msg5.metaData.toResource
      _     <- msg5.ack.toResource
      data5  = new String(msg5.data.getOrElse(Array.empty[Byte]))

      // Get final consumer info
      finalConsumerInfo <- cc.getConsumerInfo.toResource

    } yield
    // Consumer info assertions
    expect(retrievedName.contains(consumerName)) &&
      expect.eql(consumerInfo.name, consumerName) &&
      expect.eql(consumerInfo.streamName, streamName) &&
      // Message consumption assertions
      expect.eql(data1, "message 1") &&
      expect.eql(data2, "message 2") &&
      expect.eql(data3, "message 3") &&
      expect.eql(data4, "message to redeliver") &&
      expect.eql(data5, "message to redeliver") &&
      // Sequence assertions
      expect.eql(meta1.streamSequence(), 1L) &&
      expect.eql(meta1.consumerSequence(), 1L) &&
      expect.eql(meta2.streamSequence(), 2L) &&
      expect.eql(meta2.consumerSequence(), 2L) &&
      expect.eql(meta3.streamSequence(), 3L) &&
      expect.eql(meta3.consumerSequence(), 3L) &&
      // Redelivery assertions
      expect.eql(meta4.deliveredCount(), 1L) &&
      expect.eql(meta4.streamSequence(), 4L) &&
      expect.eql(meta4.consumerSequence(), 4L) &&
      expect.eql(meta5.deliveredCount(), 2L) &&
      expect.eql(meta5.streamSequence(), 4L) &&   // Same stream sequence, redelivered
      expect.eql(meta5.consumerSequence(), 5L) && // Incremented consumer sequence on redelivery
      // Final consumer state
      expect.eql(finalConsumerInfo.numPending, 0L) &&
      expect.eql(finalConsumerInfo.numAckPending, 0L)
  }

  testResource("multiple concurrent subscriptions for durable consumer") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)
      sc                        <- js.streamContext(streamName).toResource
      consumerName              <- randomConsumerName.toResource

      cc <- Resource.make(
        sc
          .createOrUpdateConsumer(
            ConsumerConfiguration
              .builder()
              .durable(consumerName)
              .ackPolicy(AckPolicy.Explicit)
              .ackWait(Duration.ofMillis(100000)) // Long timeout to avoid redeliveries
              .build()
          )
      )(_ => sc.deleteConsumer(consumerName).void)
      allMessagesQueue <- Queue.bounded[IO, (String, JetStreamMessage[IO])](10).toResource

      // Create two concurrent subscriptions that will alternately receive messages thanks to default NATS round-robin behavior
      _ <- cc.consume(msg => allMessagesQueue.offer("consumer1" -> msg) >> msg.ack)
      _ <- cc.consume(msg => allMessagesQueue.offer("consumer2" -> msg) >> msg.ack)

      // Publish multiple messages
      publisher <- js.jetStreamPublisher().toResource
      _         <- publisher.publish(subject, "message 1".getBytes()).toResource
      _         <- publisher.publish(subject, "message 2".getBytes()).toResource
      _         <- publisher.publish(subject, "message 3".getBytes()).toResource
      _         <- publisher.publish(subject, "message 4".getBytes()).toResource
      _         <- publisher.publish(subject, "message 5".getBytes()).toResource
      _         <- publisher.publish(subject, "message 6".getBytes()).toResource

      // Collect all 6 messages
      allData <- allMessagesQueue.take
        .replicateA(6)
        .map(_.map {
          case (consumerId, msg) =>
            consumerId -> new String(msg.data.getOrElse(Array.empty[Byte]))
        })
        .toResource

    } yield
    // Each subscription received 3 messages
    expect.eql(
      Map("consumer1" -> 3, "consumer2" -> 3),
      allData.groupBy(_._1).view.mapValues(_.size).toMap
    ) &&
      // All 6 messages were delivered across both subscriptions
      expect.eql(
        List(
          "message 1",
          "message 2",
          "message 3",
          "message 4",
          "message 5",
          "message 6"
        ),
        allData.map(_._2).sorted
      )
  }
}
