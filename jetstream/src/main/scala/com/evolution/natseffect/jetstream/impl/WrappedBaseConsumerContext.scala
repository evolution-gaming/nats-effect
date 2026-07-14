package com.evolution.natseffect.jetstream.impl

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import com.evolution.natseffect.impl.{closeDispatcherSafe, ConfiguringMessageHandler, JConnection, JavaWrapper}
import com.evolution.natseffect.jetstream.{BaseConsumerContext, JetStreamMessage, MessageSubscription}
import io.nats.client.ConsumeOptions

private[natseffect] class WrappedBaseConsumerContext[F[_]: Async](
  wrapped: JBaseConsumerContext,
  connection: JConnection
) extends BaseConsumerContext[F]
    with JavaWrapper[JBaseConsumerContext] {

  override def consume(
    messageHandler: JetStreamMessage[F] => F[Unit],
    consumeOptions: ConsumeOptions
  ): Resource[F, MessageSubscription[F]] =
    for {
      // Each ordered consumer gets its own sequential dispatcher to preserve message order;
      // It is used by both NATS dispatcher, and CE message handler;
      ceDispatcher <- Dispatcher.sequential[F]

      natsDispatcher <- Resource.make {
        Async[F].delay {
          val dispatcher = connection.createDispatcher(
            ConfiguringMessageHandler(
              customCeDispatcher = Some(ceDispatcher)
            )
          )
          // We assume JetStream consumers must not drop messages as slow consumers: for ordered consumers that
          // causes sequence gaps and consumer re-creation. The tradeoff is possible memory exhaustion if consumers
          // are genuinely slow, as the queue of messages then grows without bound.
          // This is a temporary solution until we find a better one (e.g., a proper back-pressure implementation).
          dispatcher.setPendingLimits(0L, 0L)
          dispatcher
        }
      } { d =>
        closeDispatcherSafe(connection, d)
      }

      handler = CEJetStreamMessageHandler[F](ceDispatcher, messageHandler)

      jMessageConsumer <- Resource.fromAutoCloseable {
        // `wrapped.consume` creates the server-side consumer via a synchronous CONSUMER.CREATE
        // request/reply (NatsMessageConsumer -> doSub -> subscribe -> _createConsumer), which can wait
        // up to the JetStream request timeout on a busy connection, hence `blocking`;
        Async[F].blocking(wrapped.consume(consumeOptions, natsDispatcher, handler))
      }

    } yield new WrappedMessageConsumer[F](jMessageConsumer)

  override def asJava: JBaseConsumerContext = wrapped
}
