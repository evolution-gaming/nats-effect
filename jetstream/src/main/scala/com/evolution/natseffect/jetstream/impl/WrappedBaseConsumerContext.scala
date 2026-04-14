package com.evolution.natseffect.jetstream.impl

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import com.evolution.natseffect.impl.{ConfiguringMessageHandler, JConnection, JavaWrapper}
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
        Async[F].delay(connection.closeDispatcher(d))
      }

      handler = CEJetStreamMessageHandler[F](ceDispatcher, messageHandler)

      jMessageConsumer <- Resource.fromAutoCloseable {
        // No blocking requests in `wrapped.consume`, only internal publish via queueing, hence `delay`;
        Async[F].delay(wrapped.consume(consumeOptions, natsDispatcher, handler))
      }

    } yield new WrappedMessageConsumer[F](jMessageConsumer)

  override def asJava: JBaseConsumerContext = wrapped
}
