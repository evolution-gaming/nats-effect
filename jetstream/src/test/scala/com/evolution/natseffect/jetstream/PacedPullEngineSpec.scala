package com.evolution.natseffect.jetstream

import cats.effect.std.Queue
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.JMessage
import com.evolution.natseffect.jetstream.impl.PacedPullEngine
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.{ActiveSubscription, Directive, PullConfig}
import io.nats.client.impl.NatsMessage
import weaver.SimpleIOSuite

import scala.concurrent.duration.*

/** Unit tests of the engine loop against a scripted directive queue - no NATS server involved. This is the payoff of the data-shaped
  * subscription boundary: pacing, restarts, terminus handling, error retries and lifecycle are exercised as pure code (classification
  * itself is covered by `BufferedPullTransportSpec` and `PullStatusInterpreterSpec`).
  */
object PacedPullEngineSpec extends SimpleIOSuite {

  private val config = PullConfig(
    batchSize = 2,
    maxBytes = 0L,
    window = 300.millis,
    idleHeartbeatMillis = 0L,
    livenessProbeAfterEmptyWindows = 2
  )

  private type JConsumerInfo = io.nats.client.api.ConsumerInfo

  private def message(n: Int): JMessage =
    NatsMessage.builder().subject(s"test.$n").data(s"payload $n".getBytes).build()

  private def fakeSubscribe(
    directives: Queue[IO, Directive],
    subscribes: Ref[IO, Int],
    pulls: Ref[IO, Int],
    failFirstSubscribe: Boolean = false
  ): Resource[IO, ActiveSubscription[IO]] =
    Resource.eval {
      subscribes.updateAndGet(_ + 1).flatMap { n =>
        IO.raiseError(new RuntimeException("subscribe failed"))
          .whenA(failFirstSubscribe && n == 1)
          .as(
            ActiveSubscription[IO](
              consumerName = s"consumer-$n",
              pull = _ => pulls.update(_ + 1),
              next = poll => poll(directives.take),
              isActive = IO.pure(true)
            )
          )
      }
    }

  private def run(
    subscribe: Resource[IO, ActiveSubscription[IO]],
    handler: JMessage => IO[Unit],
    listener: PacedConsumerListener[IO] = PacedConsumerListener.noop[IO],
    reportError: Throwable => IO[Unit] = _ => IO.unit
  ): Resource[IO, MessageSubscription[IO]] =
    PacedPullEngine.resource[IO](
      subscribe = subscribe,
      // Liveness probe: pretend the consumer exists (value is never dereferenced by the engine)
      consumerInfo = _ => IO.pure(Some(null.asInstanceOf[JConsumerInfo])),
      config = config,
      reportError = reportError,
      handler = wrapped => handler(wrapped.asJava),
      listener = listener
    )

  test("delivers messages in order and pulls per batch, not per message") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- (1 to 4).toList.traverse_(n => directives.offer(Directive.Deliver(message(n))))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      received   <- Ref.of[IO, List[String]](Nil)
      done       <- Deferred[IO, Unit]

      subscribe = fakeSubscribe(directives, subscribes, pulls)
      handler   = (msg: JMessage) => received.updateAndGet(msg.getSubject :: _).flatMap(r => done.complete(()).void.whenA(r.size == 4))

      result <- run(subscribe, handler).use { subscription =>
        for {
          _ <- done.get.timeout(5.seconds)
          // Halt the loop before reading counters: idle windows would keep adding pulls
          _     <- subscription.stop
          _     <- IO.sleep(50.millis)
          order <- received.get.map(_.reverse)
          p     <- pulls.get
          s     <- subscribes.get
        } yield expect.eql(order, List("test.1", "test.2", "test.3", "test.4")) &&
          // batch = 2: initial pull, one after each exhausted batch, plus a few idle windows -
          // far below the 4+ pulls a per-message scheme would need before delivery completed
          expect(p >= 3) &&
          expect(p <= 8) &&
          expect.eql(s, 1)
      }
    } yield result
  }

  test("a Restart directive resubscribes and continues delivering") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      // The first take reveals a gap; the second (post-restart) is delivered
      _          <- directives.offer(Directive.Restart("gap")) *> directives.offer(Directive.Deliver(message(2)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      delivered  <- Deferred[IO, String]

      subscribe = fakeSubscribe(directives, subscribes, pulls)

      result <- run(subscribe, msg => delivered.complete(msg.getSubject).void).use { _ =>
        for {
          subject <- delivered.get.timeout(5.seconds)
          s       <- subscribes.get
        } yield expect.eql(subject, "test.2") && expect.eql(s, 2)
      }
    } yield result
  }

  test("terminus and informational directives end or continue the window without failing") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      // A Skip is drained past, a PullOver ends the pull, and the next window delivers
      _          <- directives.offer(Directive.Skip)
      _          <- directives.offer(Directive.PullOver)
      _          <- directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      delivered  <- Deferred[IO, String]
      listener   <- CountingPacedListener.make

      subscribe = fakeSubscribe(directives, subscribes, pulls)

      result <- run(subscribe, msg => delivered.complete(msg.getSubject).void, listener).use { _ =>
        for {
          subject  <- delivered.get.timeout(5.seconds)
          s        <- subscribes.get
          failures <- listener.failures
        } yield expect.eql(subject, "test.1") && expect.eql(s, 1) && expect.eql(failures, 0)
      }
    } yield result
  }

  test("a Fail directive is retried with a resubscribe and reported to the listener") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- directives.offer(Directive.Fail(new Exception("Consumer Deleted"))) *> directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      delivered  <- Deferred[IO, Unit]
      listener   <- CountingPacedListener.make
      reported   <- Ref.of[IO, List[Throwable]](Nil)

      subscribe = fakeSubscribe(directives, subscribes, pulls)

      result <- run(subscribe, _ => delivered.complete(()).void, listener, e => reported.update(e :: _)).use { _ =>
        for {
          _        <- delivered.get.timeout(5.seconds)
          s        <- subscribes.get
          failures <- listener.failures
          resubs   <- listener.resubscribes
          errors   <- reported.get
        } yield expect.eql(s, 2) &&
          expect.eql(failures, 1) &&
          expect.eql(resubs, 1) &&
          expect(errors.exists(_.getMessage.contains("Consumer Deleted")))
      }
    } yield result
  }

  test("stop lets the loop finish") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      delivered  <- Deferred[IO, Unit]

      subscribe = fakeSubscribe(directives, subscribes, pulls)

      result <- run(subscribe, _ => delivered.complete(()).void).use { subscription =>
        for {
          _        <- delivered.get.timeout(5.seconds)
          _        <- subscription.stop
          stopped  <- subscription.isStopped
          finished <- awaitFinished(subscription).timeout(5.seconds).as(true)
        } yield expect(stopped) && expect(finished)
      }
    } yield result
  }

  test("a failing first subscribe is retried until it succeeds") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      delivered  <- Deferred[IO, Unit]

      subscribe = fakeSubscribe(directives, subscribes, pulls, failFirstSubscribe = true)

      result <- run(subscribe, _ => delivered.complete(()).void).use { _ =>
        for {
          _ <- delivered.get.timeout(5.seconds)
          s <- subscribes.get
        } yield expect.eql(s, 2)
      }
    } yield result
  }

  test("a deadline firing mid-handler does not interrupt processing") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      completed  <- Deferred[IO, Unit]

      subscribe = fakeSubscribe(directives, subscribes, pulls)
      // The handler outlives the 300ms window; without the masked take-to-handle step the
      // window timeout would cancel it mid-sleep and `completed` would never fire
      handler = (_: JMessage) => IO.sleep(config.window + 200.millis) *> completed.complete(()).void

      result <- run(subscribe, handler).use(_ => completed.get.timeout(5.seconds).as(success))
    } yield result
  }

  test("acquisition fails with the last error after the first-subscribe retry budget") {
    for {
      subscribes <- Ref.of[IO, Int](0)
      alwaysFailing = Resource.eval[IO, ActiveSubscription[IO]](
        subscribes.updateAndGet(_ + 1).flatMap(n => IO.raiseError(new RuntimeException(s"subscribe failed $n")))
      )
      result   <- run(alwaysFailing, _ => IO.unit).use_.attempt
      attempts <- subscribes.get
    } yield expect(result.left.exists(_.getMessage == "subscribe failed 5")) && expect.eql(attempts, 5)
  }

  test("a throwing listener does not affect consumption and is reported") {
    val throwingListener = new PacedConsumerListener[IO] {
      private def boom: IO[Unit] = IO.raiseError(new RuntimeException("listener failure"))
      override def subscribed(consumerName: String, resubscribed: Boolean): IO[Unit]        = boom
      override def resubscribing(consumerName: String, reason: String): IO[Unit]            = boom
      override def failed(error: Throwable, retryIn: FiniteDuration): IO[Unit]              = boom
      override def pullIssued(batchSize: Int): IO[Unit]                                     = boom
      override def messageProcessed(message: JetStreamMessage[IO]): IO[Unit]                = boom
      override def handlerFailed(message: JetStreamMessage[IO], error: Throwable): IO[Unit] = boom
    }

    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- (1 to 2).toList.traverse_(n => directives.offer(Directive.Deliver(message(n))))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      received   <- Ref.of[IO, Int](0)
      done       <- Deferred[IO, Unit]
      reported   <- Ref.of[IO, Int](0)

      subscribe = fakeSubscribe(directives, subscribes, pulls)
      handler   = (_: JMessage) => received.updateAndGet(_ + 1).flatMap(n => done.complete(()).void.whenA(n == 2))

      result <- run(subscribe, handler, throwingListener, _ => reported.update(_ + 1)).use { _ =>
        for {
          _ <- done.get.timeout(5.seconds)
          r <- received.get
          e <- reported.get
        } yield expect.eql(r, 2) &&
          // At least the subscribed event and both messageProcessed events failed and were reported
          expect(e >= 3)
      }
    } yield result
  }

  test("close waits for the in-flight message to finish") {
    for {
      directives <- Queue.unbounded[IO, Directive]
      _          <- directives.offer(Directive.Deliver(message(1)))
      subscribes <- Ref.of[IO, Int](0)
      pulls      <- Ref.of[IO, Int](0)
      started    <- Deferred[IO, Unit]
      finished   <- Ref.of[IO, Boolean](false)

      subscribe = fakeSubscribe(directives, subscribes, pulls)
      handler   = (_: JMessage) => started.complete(()) *> IO.sleep(200.millis) *> finished.set(true)

      result <- run(subscribe, handler).use { subscription =>
        for {
          _ <- started.get.timeout(5.seconds)
          _ <- subscription.close
          f <- finished.get
        } yield expect(f)
      }
    } yield result
  }

  private def awaitFinished(subscription: MessageSubscription[IO]): IO[Unit] =
    subscription.isFinished.flatMap {
      case true  => IO.unit
      case false => IO.sleep(50.millis) >> awaitFinished(subscription)
    }
}
