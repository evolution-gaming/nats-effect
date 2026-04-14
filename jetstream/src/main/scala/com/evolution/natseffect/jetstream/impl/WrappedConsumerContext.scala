package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import cats.implicits.toFunctorOps
import com.evolution.natseffect.impl.JConnection
import com.evolution.natseffect.jetstream.{ConsumerContext, ConsumerInfo}

private[natseffect] class WrappedConsumerContext[F[_]: Async](
  wrapped: JConsumerContext,
  connection: JConnection
) extends WrappedBaseConsumerContext(wrapped, connection)
    with ConsumerContext[F] {

  override def getConsumerName: F[String] =
    Async[F].delay(wrapped.getConsumerName)

  override def getConsumerInfo: F[ConsumerInfo] =
    Async[F].blocking(new WrappedConsumerInfo(wrapped.getConsumerInfo))

  override def getCachedConsumerInfo: F[Option[ConsumerInfo]] =
    Async[F].delay(wrapped.getCachedConsumerInfo).map(Option(_).map(new WrappedConsumerInfo(_)))

}
