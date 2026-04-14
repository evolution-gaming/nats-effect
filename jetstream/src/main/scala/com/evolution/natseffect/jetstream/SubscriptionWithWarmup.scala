package com.evolution.natseffect.jetstream

import cats.effect.kernel.DeferredSource

/** A subscription paired with a warmup completion indicator.
  *
  * <p>SubscriptionWithWarmup represents a subscription that tracks when initial message processing is complete. The warmup phase refers to
  * the time it takes to process all pending messages that existed when the subscription was created.
  *
  * <p>This is particularly useful for key-value watches where you want to: <ul> <li>Wait until the cache is populated with current values
  * <li>Know when historical replay is complete <li>Track initial synchronization time </ul>
  *
  * <p>The warmupLatch will complete with a Result indicating: <ul> <li>Success: All pending messages were processed <li>Timeout: Warmup
  * timeout was exceeded <li>Canceled: Subscription was canceled before warmup completed </ul>
  *
  * @param subscription
  *   the active message subscription
  * @param warmupLatch
  *   a deferred that completes when warmup is done
  * @see
  *   [[Warmup.Result]]
  */
final case class SubscriptionWithWarmup[F[_]](
  subscription: MessageSubscription[F],
  warmupLatch: DeferredSource[F, Warmup.Result]
)
