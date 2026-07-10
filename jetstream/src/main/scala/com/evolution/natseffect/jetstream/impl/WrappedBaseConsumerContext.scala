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
        Async[F].delay(
          connection.createDispatcher(
            ConfiguringMessageHandler(
              customCeDispatcher = Some(ceDispatcher)
            )
          )
        )
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
