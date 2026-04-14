package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import cats.implicits.*
import com.evolution.natseffect.impl.JConnection
import com.evolution.natseffect.jetstream.OrderedConsumerContext
import io.nats.client.api.OrderedConsumerConfiguration

private[natseffect] class WrappedOrderedConsumerContext[F[_]: Async](
  wrapped: JOrderedConsumerContext,
  connection: JConnection,
  val config: OrderedConsumerConfiguration
) extends WrappedBaseConsumerContext(wrapped, connection)
    with OrderedConsumerContext[F] {

  override def getConsumerName: F[Option[String]] =
    Async[F].delay(wrapped.getConsumerName).map(Option(_))
}
