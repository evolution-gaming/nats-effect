package com.evolution.natseffect.jetstream

import cats.effect.implicits.{effectResourceOps, genTemporalOps_, monadCancelOps_}
import cats.effect.kernel.Async
import cats.effect.{Deferred, Resource}
import cats.syntax.all.*
import io.nats.client.ConsumeOptions

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Utilities for tracking consumer warmup completion.
  *
  * <p>Warmup refers to the phase where a consumer processes all messages that were pending when the subscription started. This is useful
  * for applications that need to: <ul> <li>Wait for initial data load before serving requests <li>Track cache population time <li>Ensure
  * state is current before processing begins </ul>
  *
  * <p>The warmup tracking works by: <ul> <li>Recording the pending message count when the subscription starts <li>Wrapping the message
  * handler to check if pending count reaches zero <li>Completing a Deferred when all pending messages are processed <li>Handling timeout
  * and cancellation cases </ul>
  */
object Warmup {

  /** Result of a warmup phase.
    */
  sealed trait Result
  object Result {

    /** Warmup completed successfully - all pending messages were processed.
      *
      * @param time
      *   the time it took to complete warmup
      */
    case class Success(time: FiniteDuration) extends Result

    /** Warmup was canceled before completion.
      *
      * @param time
      *   the time elapsed before cancellation
      */
    case class Canceled(time: FiniteDuration) extends Result

    /** Warmup timed out before all pending messages were processed.
      *
      * @param time
      *   the configured timeout duration
      */
    case class Timeout(time: FiniteDuration) extends Result
  }

  /** Extension methods for BaseConsumerContext to enable warmup tracking.
    */
  implicit class WarmupConsumerContextOps[F[_]](val consumerContext: BaseConsumerContext[F]) extends AnyVal {

    /** Consume messages from this consumer with warmup tracking.
      *
      * <p>This method wraps the standard consume operation to track when initial pending messages are processed. The returned
      * SubscriptionWithWarmup contains a Deferred that completes when: <ul> <li>All pending messages are processed (Success) <li>The
      * timeout is reached (Timeout) <li>The subscription is canceled (Canceled) </ul>
      *
      * <p>If there are no pending messages when the subscription starts, warmup completes immediately with Success(0 seconds).
      *
      * @param handler
      *   the function to handle each message
      * @param timeout
      *   the maximum time to wait for warmup completion
      * @param consumeOptions
      *   optional consume options
      * @return
      *   Resource yielding a SubscriptionWithWarmup
      */
    def consumeWithWarmup(
      handler: JetStreamMessage[F] => F[Unit],
      timeout: FiniteDuration,
      consumeOptions: ConsumeOptions = ConsumeOptions.DEFAULT_CONSUME_OPTIONS
    )(implicit F: Async[F]): Resource[F, SubscriptionWithWarmup[F]] =
      for {
        (wrappedHandler, warmupResult) <- fromHandler[F](handler, timeout)
        subscription                   <- consumerContext.consume(wrappedHandler, consumeOptions)
        pendingCount                   <- subscription.getConsumerInfo.map(_.calculatedPending).toResource

        // In case there were no pending messages at the start, complete the warmup immediately
        _ <- warmupResult
          .complete(Result.Success(0.seconds))
          .whenA(pendingCount == 0L)
          .toResource
      } yield SubscriptionWithWarmup(subscription, warmupResult)
  }

  /** Creates a handler wrapper that will complete a Deferred once all pending messages are processed, or the timeout is reached, or the
    * processing is canceled.
    */
  private def fromHandler[F[_]: Async](
    handler: JetStreamMessage[F] => F[Unit],
    timeout: FiniteDuration
  ): Resource[F, (JetStreamMessage[F] => F[Unit], Deferred[F, Result])] = {

    def maybeCompleteWarmup(
      warmupResult: Deferred[F, Result]
    )(
      cond: F[Boolean],
      resultF: F[Result]
    ): F[Unit] =
      warmupResult.tryGet.flatMap {
        case Some(_) => Async[F].unit
        case None =>
          cond.flatMap {
            case true =>
              for {
                result <- resultF
                _      <- warmupResult.complete(result)
              } yield ()
            case false => Async[F].unit
          }
      }

    for {
      warmupResult        <- Deferred[F, Result].toResource
      maybeCompleteWarmup1 = maybeCompleteWarmup(warmupResult)

      warmupStartTime <- Async[F].monotonic.toResource
      timeMeasured     = Async[F].monotonic.map(_ - warmupStartTime)

      wrappedHandler = (jsMsg: JetStreamMessage[F]) =>
        for {
          _ <- handler(jsMsg)
          _ <- maybeCompleteWarmup1(
            jsMsg.metaData.map(_.pendingCount() == 0L),
            timeMeasured.map(Result.Success(_))
          )
        } yield ()

      // Complete the latch in case of timeout or cancellation
      _ <- Async[F]
        .background {
          maybeCompleteWarmup1(
            Async[F].pure(true),
            timeMeasured.map(Result.Timeout(_))
          )
            .delayBy(timeout)
            .onCancel {
              maybeCompleteWarmup1(
                Async[F].pure(true),
                timeMeasured.map(Result.Canceled(_))
              )
            }
        }
    } yield (wrappedHandler, warmupResult)
  }

}
