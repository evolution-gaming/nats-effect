package com.evolution.natseffect.jetstream

import cats.effect.{Deferred, IO, Resource}
import com.evolution.natseffect.jetstream.Warmup.WarmupConsumerContextOps
import com.evolution.natseffect.{Connection, Consumer, ErrorListener, Message, Nats, Options}
import io.nats.client.api.{DeliverPolicy, OrderedConsumerConfiguration, StreamConfiguration}
import weaver.GlobalRead

import scala.concurrent.duration.*

class WarmupFailureSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("warmup completes with Failed when the handler fails on the last pending message") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)
      publisher                 <- js.jetStreamPublisher().toResource
      _                         <- publisher.publish(subject, "message 1".getBytes()).toResource
      _                         <- publisher.publish(subject, "message 2".getBytes()).toResource
      _                         <- publisher.publish(subject, "message 3".getBytes()).toResource

      sc  <- js.streamContext(streamName).toResource
      occ <- sc.createOrderedConsumer(new OrderedConsumerConfiguration().deliverPolicy(DeliverPolicy.All)).toResource

      handlerError = new RuntimeException("boom on last pending message")

      // The handler fails on the last pre-published (= last pending) message; warmup must still
      // complete promptly instead of stalling until the 30s timeout
      subscription <- occ.consumeWithWarmup(
        msg =>
          if (msg.data.exists(new String(_) == "message 3")) IO.raiseError(handlerError)
          else IO.unit,
        timeout = 30.seconds
      )

      warmupResult <- subscription.warmupLatch.get.timeout(10.seconds).toResource
    } yield matches(warmupResult) { case Warmup.Result.Failed(error, _) =>
      expect(error == handlerError)
    }
  }

  testResource("warmup reports Failed with the first error when the handler fails on an earlier pending message") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)
      publisher                 <- js.jetStreamPublisher().toResource
      _                         <- publisher.publish(subject, "message 1".getBytes()).toResource
      _                         <- publisher.publish(subject, "message 2".getBytes()).toResource
      _                         <- publisher.publish(subject, "message 3".getBytes()).toResource

      sc  <- js.streamContext(streamName).toResource
      occ <- sc.createOrderedConsumer(new OrderedConsumerConfiguration().deliverPolicy(DeliverPolicy.All)).toResource

      handlerError = new RuntimeException("boom on first message")

      subscription <- occ.consumeWithWarmup(
        msg =>
          if (msg.data.exists(new String(_) == "message 1")) IO.raiseError(handlerError)
          else IO.unit,
        timeout = 30.seconds
      )

      warmupResult <- subscription.warmupLatch.get.timeout(10.seconds).toResource
    } yield matches(warmupResult) { case Warmup.Result.Failed(error, _) =>
      expect(error == handlerError)
    }
  }

  testResource("JetStream handler failure surfaces to the connection's error listener") { ctx =>
    for {
      errorDeferred <- Deferred[IO, Throwable].toResource
      errorListener = new ErrorListener[IO] {
        override def errorOccurred(conn: Connection[IO], error: String): IO[Unit]                 = IO.unit
        override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit]            = errorDeferred.complete(exp).void
        override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] = IO.unit
        override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit]               = IO.unit
        override def socketWriteTimeout(conn: Connection[IO]): IO[Unit]                           = IO.unit
      }

      connection <- Nats.connect(Options[IO]().withNatsServerUris(Seq(ctx.url())).withErrorListener(Some(errorListener)))
      js         <- JetStream.fromConnection[IO](connection).toResource
      jsm        <- js.jetStreamManagement().toResource
      streamName <- randomStreamName.toResource
      subject    <- randomSubject.toResource
      _ <- Resource.make(
        jsm.addStream(StreamConfiguration.builder().name(streamName).subjects(subject).build())
      )(_ => jsm.deleteStream(streamName).void)

      sc  <- js.streamContext(streamName).toResource
      occ <- sc.createOrderedConsumer(new OrderedConsumerConfiguration().deliverPolicy(DeliverPolicy.All)).toResource

      handlerError = new RuntimeException("handler failure that must reach the error listener")

      _ <- occ.consume(_ => IO.raiseError(handlerError))

      publisher <- js.jetStreamPublisher().toResource
      _         <- publisher.publish(subject, "message".getBytes()).toResource

      reported <- errorDeferred.get.timeout(10.seconds).toResource
    } yield expect(reported == handlerError)
  }
}
