package com.evolution.natseffect.jetstream

import cats.Applicative

import scala.concurrent.duration.FiniteDuration

/** Observability hook for paced pull consumers (see `PacedPullEngine`).
  *
  * <p>The engine invokes the listener at its lifecycle points; implementations attach logging or metrics. All methods run on the engine's
  * loop fiber, so they should be fast and must not fail - an implementation error is swallowed by the engine after being reported to the
  * connection error listener.
  *
  * <p>Typical wiring: count `messageProcessed`/`handlerFailed`/`pullIssued` as counters, alert on `resubscribing` (each occurrence is a
  * server-side consumer recreation) and on repeated `failed`. Handler and cycle errors are additionally reported to the jnats connection
  * `ErrorListener`, so plain-log visibility (e.g. via the logback module's `ErrorListenerLogger`) works without a custom listener.
  *
  * <p>Extend [[PacedConsumerListener.Noop]] to override only the events of interest.
  */
trait PacedConsumerListener[F[_]] {

  /** A subscription was established; `resubscribed` is false for the initial one and true for every recovery (each ordered recovery is a
    * new server-side consumer).
    */
  def subscribed(consumerName: String, resubscribed: Boolean): F[Unit]

  /** The subscription is about to be re-established: a sequence gap was detected, the consumer disappeared, or the subscription became
    * inactive. Only fires after an established subscription, so the consumer name is always known.
    */
  def resubscribing(consumerName: String, reason: String): F[Unit]

  /** Subscribing or the consume cycle failed; the engine retries after `retryIn`. */
  def failed(error: Throwable, retryIn: FiniteDuration): F[Unit]

  /** A pull request for `batchSize` messages was issued (one per window/batch). */
  def pullIssued(batchSize: Int): F[Unit]

  /** The handler effect for a message completed successfully. */
  def messageProcessed(message: JetStreamMessage[F]): F[Unit]

  /** The handler effect for a message failed; the engine continues with the next message. */
  def handlerFailed(message: JetStreamMessage[F], error: Throwable): F[Unit]
}

object PacedConsumerListener {

  def noop[F[_]: Applicative]: PacedConsumerListener[F] = new Noop[F]

  /** No-op base implementation; extend and override selectively. */
  class Noop[F[_]](implicit F: Applicative[F]) extends PacedConsumerListener[F] {
    override def subscribed(consumerName: String, resubscribed: Boolean): F[Unit]       = F.unit
    override def resubscribing(consumerName: String, reason: String): F[Unit]           = F.unit
    override def failed(error: Throwable, retryIn: FiniteDuration): F[Unit]             = F.unit
    override def pullIssued(batchSize: Int): F[Unit]                                    = F.unit
    override def messageProcessed(message: JetStreamMessage[F]): F[Unit]                = F.unit
    override def handlerFailed(message: JetStreamMessage[F], error: Throwable): F[Unit] = F.unit
  }
}
