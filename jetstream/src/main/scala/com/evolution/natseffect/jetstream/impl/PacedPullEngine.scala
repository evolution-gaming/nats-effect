package com.evolution.natseffect.jetstream.impl

import cats.effect.implicits.{genSpawnOps, genTemporalOps}
import cats.effect.{Async, Deferred, Outcome, Poll, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{JConnection, JMessage}
import com.evolution.natseffect.jetstream.{ConsumerInfo, JetStreamMessage, MessageSubscription, PacedConsumerListener}
import io.nats.client.{ConsumeOptions, PullRequestOptions}

import java.io.IOException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** A consume engine that paces JetStream pulls by processing: at most one processing-active pull, and the next message is taken only after
  * the handler effect for the previous one completed, so the client-side buffer is bounded by the pull budget and jnats pending-limit drops
  * \- and the ordered-consumer gap -> recreate feedback they cause - are impossible by construction.
  *
  * <p>A subscribe Resource establishes an [[PacedPullEngine.ActiveSubscription]]: a step yielding already-classified
  * [[PacedPullEngine.Directive]]s (jnats's push delivery inverted into a take and fused with status interpretation and consumer-type
  * inspection, see `BufferedPullTransport`), plus the pull and liveness handles. The loop consumes directives and owns windows, pacing,
  * backoff and lifecycle; recovery is a return value here, never a blocking call inside a message callback.
  */
private[natseffect] object PacedPullEngine {

  private val InitialRetryDelay = 100.millis
  private val MaxRetryDelay     = 5.seconds

  /** Subscribe failures before the first success fail acquisition with the last error (like the callback engine, which fails on the first
    * one); the budget absorbs ~1.5s of transient conditions. After the first success, resubscribes retry indefinitely.
    */
  private val FirstSubscribeAttempts = 5

  /** A pull terminus this long before the window deadline (e.g. the consumer is gone) is guarded with a short sleep when nothing was
    * delivered, so terminus storms cannot re-pull in a hot loop.
    */
  private val EarlyEmptyThreshold = 1.second
  private val EarlyEmptyGuard     = 500.millis

  /** Pull pacing parameters; mapped from the same jnats `ConsumeOptions` the callback engine takes. `thresholdPercent` has no equivalent
    * here (it is receipt-paced repull tuning); the idle heartbeat interval is the one jnats derives from the window.
    */
  final case class PullConfig(
    batchSize: Int,
    maxBytes: Long,
    window: FiniteDuration,
    idleHeartbeatMillis: Long,
    livenessProbeAfterEmptyWindows: Int
  )

  object PullConfig {
    def fromConsumeOptions(consumeOptions: ConsumeOptions): PullConfig =
      PullConfig(
        batchSize = consumeOptions.getBatchSize,
        maxBytes = consumeOptions.getBatchBytes,
        window = consumeOptions.getExpiresInMillis.millis,
        idleHeartbeatMillis = consumeOptions.getIdleHeartbeat,
        livenessProbeAfterEmptyWindows = 2
      )
  }

  /** Directive about a single item received on the subscription, as yielded by [[ActiveSubscription.next]] - classification (status
    * interpretation, JetStream verification, consumer-type inspection) happens behind the subscription boundary, so the loop consumes these
    * in one match. The producer subsets are types: a status can never map to Deliver (a raw status message must not reach the user
    * handler), and a data-message inspection can never end a pull or take the backoff path.
    */
  sealed trait Directive
  object Directive {

    /** The directives a data-message inspection may produce. */
    sealed trait DataDirective extends Directive

    /** The directives a status interpretation may produce. */
    sealed trait StatusDirective extends Directive

    /** A wanted data message, already verified to be a JetStream message: hand it to the handler. */
    final case class Deliver(message: JMessage) extends DataDirective

    /** Nothing to act on (filtered data, informational status): keep draining the window. */
    case object Skip extends DataDirective with StatusDirective

    /** A terminus status: this pull is finished, issue the next one. */
    case object PullOver extends StatusDirective

    /** The subscription must be re-established immediately (ordered sequence gap). */
    final case class Restart(reason: String) extends DataDirective

    /** The pull is broken (e.g. consumer deleted): resubscribe via the progressive-backoff path. */
    final case class Fail(error: Throwable) extends StatusDirective
  }

  /** One established subscription, as the engine sees it: a step yielding the next classified [[Directive]], the pull handle, and the
    * liveness check that tells a dead subscription apart from an idle one (the step just goes quiet either way). `next` must apply the
    * given `Poll` to the message wait alone, so that waiting is the only cancelable point of the engine's masked take-to-handle step and a
    * taken message is always classified. Torn down as a whole by the Resource that produced it.
    */
  final case class ActiveSubscription[F[_]](
    consumerName: String,
    pull: PullRequestOptions => F[Unit],
    next: Poll[F] => F[Directive],
    isActive: F[Boolean]
  )

  /** State of one engine subscription, from its first successful subscribe on - one value in one Ref, every transition a single atomic
    * update. The Ref is created by the first successful subscribe and delivered as the payload of the acquisition handshake, so there is no
    * before-first-subscribe case to represent: while the handshake is incomplete no handle exists and stop is impossible, and a consumer
    * name exists exactly as long as the state does (kept through backoff, stop and finish).
    */
  final private[impl] case class SubscriptionState(consumerName: String, phase: Phase) {
    def stopRequested: Boolean = phase != Phase.Running
  }

  /** Progress of the consume loop: `stop` moves Running -> Stopping (from the handle's fiber), the loop moves Stopping -> Finished when it
    * exits gracefully. `close` cancels the loop instead, leaving the phase at Stopping - so Finished cannot precede a stop by construction.
    */
  sealed private[impl] trait Phase
  private[impl] object Phase {
    case object Running  extends Phase
    case object Stopping extends Phase
    case object Finished extends Phase
  }

  /** Builds the standard error reporter: engine and handler failures go to the jnats connection error listener, so existing log wiring
    * (e.g. the logback module) covers the paced engine. Reporter failures are swallowed - reporting must never affect the loop.
    */
  def connectionErrorReporter[F[_]](connection: JConnection)(implicit F: Async[F]): Throwable => F[Unit] =
    e =>
      F.delay {
        Option(connection.getOptions.getErrorListener).foreach { errorListener =>
          val exception = e match {
            case e: Exception => e
            case other        => new Exception(other)
          }
          errorListener.exceptionOccurred(connection, exception)
        }
      }.handleError(_ => ())

  /** Runs the paced consume loop as a Resource yielding the subscription handle. All dependencies are data and functions - there is no
    * consumer-type trait to implement.
    *
    * @param subscribe
    *   establishes the standing subscription (the transport internals are created and torn down together); evaluated initially and after
    *   every restart. For ordered consumers each evaluation creates the next ephemeral consumer continuing after the last delivered stream
    *   sequence; for named consumers it binds to the existing consumer.
    * @param consumerInfo
    *   server-side consumer lookup, None when the consumer no longer exists; drives the liveness probe and `getConsumerInfo`
    */
  def resource[F[_]: Async](
    subscribe: Resource[F, ActiveSubscription[F]],
    consumerInfo: String => F[Option[JConsumerInfo]],
    config: PullConfig,
    reportError: Throwable => F[Unit],
    handler: JetStreamMessage[F] => F[Unit],
    listener: PacedConsumerListener[F]
  ): Resource[F, MessageSubscription[F]] =
    for {
      firstSubscribe <- Resource.eval(Deferred[F, Either[Throwable, Ref[F, SubscriptionState]]])
      // The getCachedConsumerInfo cache, owned by the subscription handle alone - the loop never touches it
      lastInfo <- Resource.eval(Ref.of[F, Option[JConsumerInfo]](None))
      fiber    <- Resource.make(run(subscribe, consumerInfo, config, reportError, handler, listener, firstSubscribe).start)(_.cancel)

      // Acquisition completes once the first subscribe succeeds - with the subscription state as
      // the handshake's payload - or fails with the last error after FirstSubscribeAttempts
      // failures. The wait is cancelable; transient conditions are absorbed by the backoff, and a
      // permanently broken subscribe fails the consume instead of hanging it
      state <- Resource.eval(firstSubscribe.get.flatMap(_.liftTo[F]))
    } yield new PacedMessageSubscription[F](consumerInfo, state, lastInfo, fiber.cancel)

  private def run[F[_]](
    subscribe: Resource[F, ActiveSubscription[F]],
    consumerInfo: String => F[Option[JConsumerInfo]],
    config: PullConfig,
    reportError: Throwable => F[Unit],
    handler: JetStreamMessage[F] => F[Unit],
    listener: PacedConsumerListener[F],
    firstSubscribe: Deferred[F, Either[Throwable, Ref[F, SubscriptionState]]]
  )(implicit F: Async[F]): F[Unit] = {

    // Loop structure, outermost first:
    //   subscribeLoop - one iteration per subscription lifetime: establish it via the subscribe Resource,
    //                   run pullLoop until it ends; owns the resubscribe and backoff policy
    //   pullLoop      - one iteration per pull request: issue the pull, drain its window; owns the
    //                   consecutive-empty-window accounting and the consumer liveness probe
    //   drainWindow   - one iteration per message of the current window, paced by the handler;
    //                   reports back only how the window ended (WindowOutcome)

    // A misbehaving listener must not affect the consume loop
    def guarded(event: F[Unit]): F[Unit] =
      event.handleErrorWith(reportError)

    // Stop-flag shortcut shared by the loop levels that hold the state: once stop was requested,
    // yield the level's stop value instead of running the next step
    def unlessStopped[A](state: Ref[F, SubscriptionState])(onStop: A)(step: F[A]): F[A] =
      state.get.flatMap(s => if (s.stopRequested) onStop.pure[F] else step)

    def handleMessage(message: JMessage): F[Unit] = {
      val wrapped = new WrappedJetStreamMessage[F](message)
      // Handler errors are reported and processing continues: a failing handler must not tear down
      // the subscription (matches the error-listener semantics of the jnats dispatcher engine)
      handler(wrapped).attempt.flatMap {
        case Right(_) => guarded(listener.messageProcessed(wrapped))
        case Left(e)  => reportError(e) *> guarded(listener.handlerFailed(wrapped, e))
      }
    }

    // One take-to-handle step, bracket-shaped: the message wait (behind poll) is the only cancelable
    // point, so once a message is taken, its classification and the handler always run to completion.
    // A window deadline or close firing mid-step can at worst discard the step's *result* - the
    // window is then miscounted as idle - never a taken message: this is what makes drops impossible
    def awaitDirective(active: ActiveSubscription[F]): F[Directive] =
      F.uncancelable { poll =>
        active.next(poll).flatTap {
          case Directive.Deliver(message) => handleMessage(message)
          case _                          => F.unit
        }
      }

    def probeAlive(state: Ref[F, SubscriptionState]): F[Boolean] =
      state.get.flatMap { s =>
        // Only a definite "consumer not found" counts as dead; transient lookup errors must not
        // cause restarts of a healthy subscription
        consumerInfo(s.consumerName).attempt.map {
          case Right(None) => false
          case _           => true
        }
      }

    // Stop can only arrive through the subscription handle, and the handle exists only once the
    // handshake completed successfully - anything else is proof that no stop was requested
    def stopRequested: F[Boolean] =
      firstSubscribe.tryGet.flatMap {
        case Some(Right(state)) => state.get.map(_.stopRequested)
        case _                  => F.pure(false)
      }

    // The first establishment creates the state and completes the acquisition handshake with it;
    // every later one updates the name, preserving the phase so a stop that arrived while the
    // subscription was being established survives
    def register(active: ActiveSubscription[F]): F[Ref[F, SubscriptionState]] =
      firstSubscribe.tryGet.flatMap {
        case Some(Right(state)) =>
          state.update(_.copy(consumerName = active.consumerName)) *>
            guarded(listener.subscribed(active.consumerName, resubscribed = true)).as(state)
        case _ =>
          Ref.of[F, SubscriptionState](SubscriptionState(active.consumerName, Phase.Running)).flatTap { state =>
            firstSubscribe.complete(Right(state)).void *>
              guarded(listener.subscribed(active.consumerName, resubscribed = false))
          }
      }

    def subscribeLoop(retryDelay: FiniteDuration, failedAttempts: Int): F[Unit] =
      stopRequested.flatMap { stopped =>
        if (stopped) F.unit
        else
          subscribe
            .use(active => register(active).flatMap(state => pullLoop(state, active).tupleLeft(state)))
            .attempt
            .flatMap {
              case Right((_, CycleEnd.Stopped))                        => F.unit
              case Right((state, CycleEnd.Resubscribe(reason, after))) =>
                // Data-driven recoveries (gap, consumer-gone) re-establish immediately; an inactive
                // subscription waits a beat so a flapping connection cannot drive a tight
                // CONSUMER.CREATE loop. Ordered subscribes continue from the last delivered sequence
                state.get.flatMap(s => guarded(listener.resubscribing(s.consumerName, reason))) *>
                  F.sleep(after) *> subscribeLoop(InitialRetryDelay, failedAttempts = 0)
              case Left(e) =>
                val failures = failedAttempts + 1
                firstSubscribe.tryGet.flatMap {
                  case None if failures >= FirstSubscribeAttempts =>
                    // The consume never got a subscription: fail acquisition with the last error
                    // and end the loop - the handle that could ask for anything will never exist
                    reportError(e) *> firstSubscribe.complete(Left(e)).void
                  case _ =>
                    // Status errors, transport failures, and the first subscribes alike: keep
                    // retrying with progressive backoff (only pre-acquisition attempts are budgeted)
                    reportError(e) *> guarded(listener.failed(e, retryDelay)) *>
                      F.sleep(retryDelay) *> subscribeLoop((retryDelay * 2).min(MaxRetryDelay), failures)
                }
            }
      }

    def pullLoop(state: Ref[F, SubscriptionState], active: ActiveSubscription[F]): F[CycleEnd] = {

      def issuePull: F[Unit] = {
        val builder = PullRequestOptions
          .builder(config.batchSize)
          .expiresIn(config.window.toMillis)
        if (config.maxBytes > 0L) builder.maxBytes(config.maxBytes)
        if (config.idleHeartbeatMillis > 0L) builder.idleHeartbeat(config.idleHeartbeatMillis)
        active.pull(builder.build()) *> guarded(listener.pullIssued(config.batchSize))
      }

      def loop(emptyWindows: Int): F[CycleEnd] =
        unlessStopped[CycleEnd](state)(CycleEnd.Stopped) {
          for {
            _        <- issuePull
            deadline <- F.monotonic.map(_ + config.window)
            outcome  <- drainWindow(state, active, deadline, remaining = config.batchSize, deliveredAny = false)
            end <- outcome match {
              case WindowOutcome.Handled     => loop(0)
              case end: CycleEnd             => F.pure(end)
              case WindowOutcome.Idle(early) =>
                // Consecutive idle windows eventually trigger a consumer liveness probe: a dead
                // consumer produces exactly this silence. The guard sleep throttles terminus-like
                // early ends so they cannot re-pull in a hot loop
                val empties = emptyWindows + 1
                val guard   = F.sleep(EarlyEmptyGuard).whenA(early)
                if (empties >= config.livenessProbeAfterEmptyWindows)
                  guard *> probeAlive(state).flatMap {
                    case true  => loop(0)
                    case false => F.pure[CycleEnd](CycleEnd.Resubscribe("consumer no longer exists", Duration.Zero))
                  }
                else guard *> loop(empties)
            }
          } yield end
        }

      loop(0)
    }

    def drainWindow(
      state: Ref[F, SubscriptionState],
      active: ActiveSubscription[F],
      deadline: FiniteDuration,
      remaining: Int,
      deliveredAny: Boolean
    ): F[WindowOutcome] = {

      def windowOver(early: Boolean): WindowOutcome =
        if (deliveredAny) WindowOutcome.Handled else WindowOutcome.Idle(early)

      unlessStopped[WindowOutcome](state)(CycleEnd.Stopped) {
        F.monotonic.flatMap { now =>
          val timeLeft = deadline - now
          if (timeLeft <= Duration.Zero) F.pure(windowOver(early = false))
          else
            awaitDirective(active)
              .timeout(timeLeft.max(1.milli))
              .flatMap {
                case Directive.Deliver(_) =>
                  // Already handled inside the masked step
                  if (remaining <= 1) F.pure[WindowOutcome](WindowOutcome.Handled)
                  else drainWindow(state, active, deadline, remaining - 1, deliveredAny = true)
                case Directive.Skip     => drainWindow(state, active, deadline, remaining, deliveredAny)
                case Directive.PullOver =>
                  // The pull terminated server-side; ending well before the deadline is what
                  // pullLoop's hot-loop guard keys on
                  F.monotonic.map(end => windowOver(early = (deadline - end) > EarlyEmptyThreshold))
                case Directive.Restart(reason) =>
                  F.pure[WindowOutcome](CycleEnd.Resubscribe(reason, Duration.Zero))
                case Directive.Fail(e) =>
                  // The one recovery that is not a return value: raised so subscribeLoop's
                  // attempt routes it through the progressive-backoff path
                  F.raiseError[WindowOutcome](e)
              }
              .recoverWith {
                case _: TimeoutException =>
                  // The window expired waiting (never "early") - or the deadline fired during a
                  // masked step, whose message was still fully processed and only this window's
                  // label was lost. A dead subscription goes quiet the same way, so distinguish
                  // via the liveness handle
                  active.isActive.flatMap {
                    case true => F.pure(windowOver(early = false))
                    case false =>
                      unlessStopped[WindowOutcome](state)(CycleEnd.Stopped)(
                        F.pure(CycleEnd.Resubscribe("subscription inactive", InitialRetryDelay))
                      )
                  }
              }
        }
      }
    }

    F.guaranteeCase(subscribeLoop(InitialRetryDelay, failedAttempts = 0)) {
      case Outcome.Succeeded(_) =>
        // A graceful exit after a stop implies a successful handshake; an exhausted first-subscribe
        // budget also exits gracefully but has no state to finish
        firstSubscribe.tryGet.flatMap(_.flatMap(_.toOption).traverse_(_.update(_.copy(phase = Phase.Finished))))
      case _ => F.unit
    }
  }

  /** How a single pull window ended, reported by drainWindow to pullLoop. Cycle endings ([[CycleEnd]]) are a sub-trait: a stop or restart
    * is detected inside a window, so every cycle ending is also a window outcome; pullLoop consumes the window-level cases and passes the
    * cycle endings up as its narrowed return type.
    */
  sealed private trait WindowOutcome
  private object WindowOutcome {

    /** At least one message was handled (the batch may or may not be exhausted); pull again. */
    case object Handled extends WindowOutcome

    /** Nothing was handled; `early` marks a terminus-like end well before the deadline. */
    final case class Idle(early: Boolean) extends WindowOutcome
  }

  /** How one subscription's consume cycle ended - the subset of window outcomes that escapes pullLoop to subscribeLoop. */
  sealed private trait CycleEnd extends WindowOutcome
  private object CycleEnd {
    case object Stopped                                                 extends CycleEnd
    final case class Resubscribe(reason: String, after: FiniteDuration) extends CycleEnd
  }
}

final private[natseffect] class PacedMessageSubscription[F[_]](
  consumerInfo: String => F[Option[JConsumerInfo]],
  state: Ref[F, PacedPullEngine.SubscriptionState],
  lastInfo: Ref[F, Option[JConsumerInfo]],
  cancelLoop: F[Unit]
)(implicit F: Async[F])
    extends MessageSubscription[F] {

  import PacedPullEngine.Phase

  override def getConsumerName: F[String] =
    state.get.map(_.consumerName)

  override def getConsumerInfo: F[ConsumerInfo] =
    state.get.flatMap { s =>
      consumerInfo(s.consumerName).flatMap {
        case Some(info) => lastInfo.set(Some(info)).as(new WrappedConsumerInfo(info))
        case None       => F.raiseError(new IOException(s"Consumer ${s.consumerName} no longer exists"))
      }
    }

  override def getCachedConsumerInfo: F[Option[ConsumerInfo]] =
    lastInfo.get.map(_.map(new WrappedConsumerInfo(_)))

  /** Stop initiating new pulls and message takes. The in-flight message finishes processing; already-received messages of the current pull
    * are discarded. An idle subscription exits once its pull window expires; `close` exits promptly while waiting, but also lets an
    * in-flight message finish - the engine's take-to-handle step is masked, so cancellation never tears processing mid-message.
    */
  override def stop: F[Unit] =
    state.update(s => if (s.phase == Phase.Running) s.copy(phase = Phase.Stopping) else s)

  override def close: F[Unit] =
    stop *> cancelLoop

  override def isStopped: F[Boolean] =
    state.get.map(_.stopRequested)

  override def isFinished: F[Boolean] =
    state.get.map(_.phase == Phase.Finished)
}
