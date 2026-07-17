package com.evolution.natseffect.jetstream

import cats.effect.{IO, Ref}

import scala.concurrent.duration.FiniteDuration

/** Test instrument: counts paced-engine lifecycle events, all in one Ref. `resubscribes` (via `subscribed(resubscribed = true)`) covers
  * every recovery including error-path ones; `resubscribings` covers only the explicit gap/liveness/inactive signal.
  */
final class CountingPacedListener(counts: Ref[IO, CountingPacedListener.Counts]) extends PacedConsumerListener.Noop[IO] {

  def subscribes: IO[Int]      = counts.get.map(_.subscribes)
  def resubscribes: IO[Int]    = counts.get.map(_.resubscribes)
  def resubscribings: IO[Int]  = counts.get.map(_.resubscribings)
  def failures: IO[Int]        = counts.get.map(_.failures)
  def pulls: IO[Int]           = counts.get.map(_.pulls)
  def processed: IO[Int]       = counts.get.map(_.processed)
  def handlerFailures: IO[Int] = counts.get.map(_.handlerFailures)

  override def subscribed(consumerName: String, resubscribed: Boolean): IO[Unit] =
    counts.update(c => c.copy(subscribes = c.subscribes + 1, resubscribes = c.resubscribes + (if (resubscribed) 1 else 0)))

  override def resubscribing(consumerName: String, reason: String): IO[Unit] =
    counts.update(c => c.copy(resubscribings = c.resubscribings + 1))

  override def failed(error: Throwable, retryIn: FiniteDuration): IO[Unit] =
    counts.update(c => c.copy(failures = c.failures + 1))

  override def pullIssued(batchSize: Int): IO[Unit] =
    counts.update(c => c.copy(pulls = c.pulls + 1))

  override def messageProcessed(message: JetStreamMessage[IO]): IO[Unit] =
    counts.update(c => c.copy(processed = c.processed + 1))

  override def handlerFailed(message: JetStreamMessage[IO], error: Throwable): IO[Unit] =
    counts.update(c => c.copy(handlerFailures = c.handlerFailures + 1))
}

object CountingPacedListener {

  final case class Counts(
    subscribes: Int = 0,
    resubscribes: Int = 0,
    resubscribings: Int = 0,
    failures: Int = 0,
    pulls: Int = 0,
    processed: Int = 0,
    handlerFailures: Int = 0
  )

  def make: IO[CountingPacedListener] =
    Ref.of[IO, Counts](Counts()).map(new CountingPacedListener(_))
}
