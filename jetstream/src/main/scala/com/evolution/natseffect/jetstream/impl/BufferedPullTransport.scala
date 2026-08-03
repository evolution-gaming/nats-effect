package com.evolution.natseffect.jetstream.impl

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{CEMessageHandler, ConfiguringMessageHandler, JConnection, JMessage}
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.{ActiveSubscription, Directive}
import io.nats.client.impl.JNatsPullSubscriptionImplOps
import io.nats.client.{MessageHandler, PullSubscribeOptions}

/** Transport for the paced engine: a JetStream pull subscription attached to a dispatcher whose exclusive default handler feeds a queue as
  * an effect on a per-subscription sequential CE dispatcher (see `ConfiguringMessageHandler.exclusiveDefaultHandler`; exclusivity bypasses
  * the per-subscription jnats wrapper, so raw messages - statuses included - reach the queue).
  *
  * <p>The queue is a push -> pull inverter: jnats delivers by pushing; the engine consumes by taking, which is what lets one sequential
  * loop own pacing, ordering, recovery and lifecycle. The queue is unbounded structurally but bounded in practice by the engine's pull
  * budget - and the dispatcher's pending limits are pinned to 0 here, so nothing can be silently dropped regardless of the connection-wide
  * limit configuration. The subscription's `next` step fuses [[classify]] onto the take, so the engine only ever sees [[Directive]]s.
  *
  * <p>The (offer dispatcher, queue, nats dispatcher, subscription) quadruple lives and dies as one Resource: a resubscribe discards the
  * whole thing, so messages of a dead subscription can never leak into the next one (no straggler filtering needed). The nats dispatcher is
  * closed before the offer dispatcher, in that order, so offer effects are not submitted to a closed CE dispatcher (bar the same in-flight
  * teardown race the callback engine has always had).
  */
private[natseffect] object BufferedPullTransport {

  // jnats requires a per-subscription handler; the exclusive default handler bypasses it entirely
  private val NoopHandler: MessageHandler = _ => ()

  def open[F[_]: Async](
    js: JJetStream,
    connection: JConnection,
    subscribeOptions: PullSubscribeOptions,
    inspect: JMessage => F[Directive.DataDirective]
  ): Resource[F, ActiveSubscription[F]] =
    for {
      // Sequential, so the factory's effect dispatch preserves the reader thread's arrival order
      offerDispatcher <- Dispatcher.sequential[F]
      queue           <- Resource.eval(Queue.unbounded[F, JMessage])

      queueFeed = CEMessageHandler[F](offerDispatcher, message => queue.offer(message.asJava))

      natsDispatcher <- Resource.make {
        Async[F].delay {
          val dispatcher = connection.createDispatcher(
            ConfiguringMessageHandler[F](defaultMessageHandler = Some(queueFeed), exclusiveDefaultHandler = true)
          )
          // The paced engine cannot tolerate drops (for ordered consumers a drop is a sequence gap
          // forcing a consumer recreation); pin the limits regardless of the connection-wide config
          dispatcher.setPendingLimits(0, 0)
          dispatcher
        }
      }(dispatcher => Async[F].delay(connection.closeDispatcher(dispatcher)).attempt.void)

      // Creates the server-side consumer (ordered/ephemeral) or binds (named) - a blocking round-trip.
      // Torn down by closeDispatcher above: dispatcher-owned subscriptions are unsubscribed with it
      subscription <- Resource.eval(
        Async[F].interruptible(js.subscribe(null, natsDispatcher, NoopHandler, subscribeOptions))
      )

      // The engine's state machine relies on a name existing from the first successful subscribe on.
      // A pull subscription always has one (bind, or the CONSUMER.CREATE response); should that
      // invariant ever break, fail the subscribe loudly into the engine's retry path
      consumerName <- Resource.eval(
        Async[F]
          .delay(Option(subscription.getConsumerName))
          .flatMap(_.liftTo[F](new IllegalStateException("The pull subscription reports no consumer name")))
      )
      // jnats publishes every pull request with its own reply subject, and the server addresses everything
      // belonging to that pull, statuses included, to that subject. The subject is taken from the same internal
      // jnats call that computes it (see `pullReturningSubject`), so it cannot drift from what is on the wire.
      // Nothing can arrive before the first pull, so the initial value only needs to never equal a real subject.
      lastPullSubject <- Resource.eval(Ref.of[F, String](""))
    } yield ActiveSubscription(
      consumerName = consumerName,
      pull = options => subscription.pullReturningSubject(options).flatMap(lastPullSubject.set),
      // Per the ActiveSubscription contract, poll applies to the wait alone: a taken message is
      // always classified, whatever cancellation is in flight
      next = poll => poll(queue.take).flatMap(classify(inspect, lastPullSubject.get)),
      tryNext = queue.tryTake.flatMap(_.traverse(classify(inspect, lastPullSubject.get))),
      isActive = Async[F].delay(subscription.isActive)
    )

  /** Classification of everything the subscription queue can carry, fused onto the take: statuses go to the interpreter, and data messages
    * are verified to be JetStream messages (anything else on the deliver subject is dropped as noise) before the consumer-type inspection
    * vets them. This is the single site that upholds what [[PacedPullEngine.Directive.Deliver]] promises - a genuine JetStream message.
    *
    * <p>A status is interpreted only when it answers the pull that is live now (statuses arrive on the reply subject of the pull they
    * belong to, see `currentPullSubject` above); a status for an earlier pull is skipped as stale. The routine case: the engine's local
    * window deadline is armed when the pull is published, while the server starts the same `expiresIn` clock only on receipt, so on an idle
    * consumer the local deadline always fires first and the server's expiry `408` for the pull that just ended arrives at the head of the
    * *next* window. Let through, it would end that window on the spot - indistinguishable from a dead consumer answering instantly - which
    * cost a 500ms early-terminus guard sleep and a redundant second pull per window on a perfectly healthy consumer. Data messages carry no
    * pull identity worth checking: a straggler delivered on the previous pull is still a wanted message and is handled normally.
    */
  private[natseffect] def classify[F[_]: Async](
    inspect: JMessage => F[Directive.DataDirective],
    currentPullSubject: F[String]
  )(message: JMessage): F[Directive] =
    Option(message.getStatus) match {
      case Some(status) =>
        currentPullSubject
          .map(pullSubject =>
            if (message.getSubject == pullSubject) PullStatusInterpreter.interpret(status)
            else Directive.Skip
          )
          .widen
      case None if !message.isJetStream => (Directive.Skip: Directive).pure[F]
      case None                         => inspect(message).widen
    }
}
