package com.evolution.natseffect.jetstream.impl

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{JConnection, JMessage}
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.{ActiveSubscription, Directive, PullConfig}
import com.evolution.natseffect.jetstream.{
  ConsumerContext,
  ConsumerInfo,
  JetStreamMessage,
  MessageSubscription,
  OrderedConsumerContext,
  PacedConsumerListener
}
import io.nats.client.api.{AckPolicy, ConsumerConfiguration, DeliverPolicy, OrderedConsumerConfiguration}
import io.nats.client.{ConsumeOptions, JetStreamApiException, PullSubscribeOptions}

import java.io.IOException
import scala.concurrent.duration.*

/** Consumer-type subscribe recipes and contexts for the [[PacedPullEngine]]. The contexts implement the same public traits as the
  * callback-engine wrappers, so `Warmup` and the KeyValue stack run on either engine unchanged.
  */
private[natseffect] object PacedConsumerContexts {

  private val ConsumerNotFoundApiError = 10014

  /** Position of an ordered consumer in the stream: the consumer sequence expected next from the current server-side consumer, and the
    * stream sequence of the last delivered message (0 before anything was delivered).
    */
  final private case class OrderedPosition(expectedConsumerSeq: Long, lastStreamSeq: Long)

  private object OrderedPosition {
    val Initial: OrderedPosition = OrderedPosition(expectedConsumerSeq = 1L, lastStreamSeq = 0L)
  }

  /** State of an ordered paced context - one value in one Ref, so every transition (taking or releasing the consume slot, the subscribe
    * reset-and-read, the per-message position advance, the name publication) is a single atomic update. `lastConsumerName` and `position`
    * outlive individual consume calls: sequential consumes continue after the last delivered stream sequence (matching the jnats ordered
    * context, `highestSeq` there), and `getConsumerName` answers between and after consumes - its None is real public API state, an ordered
    * context has no consumer until the first consume.
    */
  final private case class OrderedContextState(
    consumeActive: Boolean,
    lastConsumerName: Option[String],
    position: OrderedPosition
  )

  private object OrderedContextState {
    val Initial: OrderedContextState =
      OrderedContextState(consumeActive = false, lastConsumerName = None, position = OrderedPosition.Initial)
  }

  /** An ordered consumer context that owns the ordered semantics itself: the consumer recipe, the consumer-sequence gap check and
    * recreate-from-last-sequence all live in the ordered subscribe step, with no dependence on the jnats ordered machinery.
    */
  def ordered[F[_]: Async](
    js: JJetStream,
    jsm: JJetStreamManagement,
    streamName: String,
    configuration: OrderedConsumerConfiguration,
    connection: JConnection,
    listener: PacedConsumerListener[F]
  ): F[OrderedConsumerContext[F]] =
    Ref.of[F, OrderedContextState](OrderedContextState.Initial).map { state =>
      new OrderedConsumerContext[F] {

        override def config: OrderedConsumerConfiguration = configuration

        override def getConsumerName: F[Option[String]] = state.get.map(_.lastConsumerName)

        override def consume(
          handler: JetStreamMessage[F] => F[Unit],
          consumeOptions: ConsumeOptions
        ): Resource[F, MessageSubscription[F]] = {
          val pullConfig = PullConfig.fromConsumeOptions(consumeOptions)
          for {
            _ <- singleConsumeSlot
            subscription <- PacedPullEngine.resource(
              subscribe = orderedSubscribe(js, streamName, configuration, connection, pullConfig.window, state),
              consumerInfo = lookupConsumerInfo(jsm, streamName, _),
              config = pullConfig,
              reportError = PacedPullEngine.connectionErrorReporter(connection),
              handler = handler,
              listener = listener
            )
          } yield subscription
        }

        // The engine has consumer-free moments between subscriptions, so single-instance semantics
        // for ordered consumers are enforced here for the whole subscription lifetime
        private def singleConsumeSlot: Resource[F, Unit] =
          Resource.make {
            state.modify { current =>
              if (current.consumeActive)
                (
                  current,
                  Async[F].raiseError[Unit](
                    new IOException(
                      "The ordered consumer is already receiving messages. Ordered Consumer does not allow multiple instances at time."
                    )
                  )
                )
              else (current.copy(consumeActive = true), Async[F].unit)
            }.flatten
          }(_ => state.update(_.copy(consumeActive = false)))
      }: OrderedConsumerContext[F]
    }

  /** A consumer context bound to an existing (durable or server-named) consumer. Multiple concurrent consumes are allowed, like with the
    * callback engine; messages are distributed across their outstanding pulls by the server.
    */
  def named[F[_]: Async](
    js: JJetStream,
    jsm: JJetStreamManagement,
    streamName: String,
    consumerName: String,
    connection: JConnection,
    listener: PacedConsumerListener[F]
  ): ConsumerContext[F] =
    new ConsumerContext[F] {

      override def getConsumerName: F[String] = consumerName.pure[F]

      override def getConsumerInfo: F[ConsumerInfo] =
        Async[F].blocking(new WrappedConsumerInfo(jsm.getConsumerInfo(streamName, consumerName)))

      override def getCachedConsumerInfo: F[Option[ConsumerInfo]] = none[ConsumerInfo].pure[F]

      override def consume(
        handler: JetStreamMessage[F] => F[Unit],
        consumeOptions: ConsumeOptions
      ): Resource[F, MessageSubscription[F]] =
        PacedPullEngine.resource(
          subscribe = BufferedPullTransport.open(
            js,
            connection,
            PullSubscribeOptions.bind(streamName, consumerName),
            // Bound consumers deliver everything the server sends; acknowledgement is the handler's business
            inspect = message => (Directive.Deliver(message): Directive.DataDirective).pure[F]
          ),
          consumerInfo = lookupConsumerInfo(jsm, streamName, _),
          config = PullConfig.fromConsumeOptions(consumeOptions),
          reportError = PacedPullEngine.connectionErrorReporter(connection),
          handler = handler,
          listener = listener
        )
    }

  /** The subscribe step of an ordered paced consumer - it owns the ordered semantics: the consumer recipe, the recreate-from-last-sequence
    * continuation and the consumer-sequence gap check (via the transport's inspect hook). Each evaluation creates a fresh ephemeral
    * consumer and publishes its name to the context state, so `getConsumerName` answers between and after consumes.
    */
  private def orderedSubscribe[F[_]: Async](
    js: JJetStream,
    streamName: String,
    configuration: OrderedConsumerConfiguration,
    connection: JConnection,
    pullWindow: FiniteDuration,
    state: Ref[F, OrderedContextState]
  ): Resource[F, ActiveSubscription[F]] = {

    /** The ordered gap check; the transport has already verified the message is a JetStream one. Advancing the position here, before the
      * handler, is safe because the engine's take-to-handle step is masked: nothing can interrupt between this advance and handler
      * completion (and a process crash discards the in-memory position with everything else).
      */
    def sequenceGapCheck(message: JMessage): F[Directive.DataDirective] =
      Async[F].delay(message.metaData()).flatMap { meta =>
        state.modify { current =>
          if (meta.consumerSequence() == current.position.expectedConsumerSeq)
            (
              current.copy(
                position = OrderedPosition(
                  expectedConsumerSeq = current.position.expectedConsumerSeq + 1,
                  lastStreamSeq = meta.streamSequence()
                )
              ),
              Directive.Deliver(message): Directive.DataDirective
            )
          else
            (
              current,
              Directive.Restart(
                s"consumer sequence gap: expected ${current.position.expectedConsumerSeq}, received ${meta.consumerSequence()}"
              ): Directive.DataDirective
            )
        }
      }

    /** Generous enough that an application stall between pulls does not get the consumer reaped; a reaped consumer is recovered by the
      * liveness probe -> resubscribe path anyway.
      */
    def inactiveThreshold: java.time.Duration =
      java.time.Duration.ofMillis((pullWindow * 2).max(5.minutes).toMillis)

    /** The ordered-consumer recipe, as jnats `SubscribeOptions` applies it for ordered pull subscriptions, on top of the user's ordered
      * configuration. A restart resumes immediately after the last delivered stream sequence, like the jnats ordered recreate does.
      */
    def consumerConfiguration(lastStreamSeq: Long): ConsumerConfiguration = {
      val base = ConsumerConfiguration
        .builder()
        .filterSubjects(configuration.getFilterSubjects)
        .deliverPolicy(configuration.getDeliverPolicy)
        .startSequence(configuration.getStartSequence)
        .startTime(configuration.getStartTime)
        .replayPolicy(configuration.getReplayPolicy)
        .headersOnly(configuration.getHeadersOnly)
        .ackPolicy(AckPolicy.None)
        .maxDeliver(1)
        .ackWait(java.time.Duration.ofHours(22))
        .memStorage(true)
        .numReplicas(1)
        .inactiveThreshold(inactiveThreshold)
      // While nothing has been delivered yet (lastStreamSeq == 0), a resubscribe deliberately
      // replays the user's original deliver policy (LastPerSubject / ByStartSequence / startTime)
      // rather than forcing sequence 1 - only after the first delivery does continuation kick in
      val positioned =
        if (lastStreamSeq > 0L)
          base
            .deliverPolicy(DeliverPolicy.ByStartSequence)
            .startSequence(Math.max(1L, lastStreamSeq + 1))
            .startTime(null)
        else base
      positioned.build()
    }

    for {
      // A new server-side consumer numbers its deliveries from 1 again; the stream position is
      // what carries over between consumers
      lastSeq <- Resource.eval(
        state.modify(current => (current.copy(position = current.position.copy(expectedConsumerSeq = 1L)), current.position.lastStreamSeq))
      )
      active <- BufferedPullTransport.open(
        js,
        connection,
        PullSubscribeOptions
          .builder()
          .stream(streamName)
          .configuration(consumerConfiguration(lastSeq))
          .build(),
        inspect = sequenceGapCheck
      )
      // The name must outlive this subscription (getConsumerName works between and after consumes),
      // so it is published to the context state as part of establishing the subscription
      _ <- Resource.eval(state.update(_.copy(lastConsumerName = Some(active.consumerName))))
    } yield active
  }

  private def lookupConsumerInfo[F[_]: Async](
    jsm: JJetStreamManagement,
    streamName: String,
    consumerName: String
  ): F[Option[JConsumerInfo]] =
    Async[F]
      .blocking(Option(jsm.getConsumerInfo(streamName, consumerName)))
      .recover {
        // Consumer-not-found detection as in jnats lookupConsumerInfo; the error-code fallback is
        // for server versions that did not provide api error codes
        case e: JetStreamApiException
            if e.getApiErrorCode == ConsumerNotFoundApiError ||
              (e.getErrorCode == 404 && Option(e.getErrorDescription).exists(_.contains("consumer"))) =>
          none[JConsumerInfo]
      }
}
