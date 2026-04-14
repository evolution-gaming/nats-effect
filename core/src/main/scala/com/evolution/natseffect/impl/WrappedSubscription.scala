package com.evolution.natseffect.impl

import cats.effect.Sync
import cats.effect.implicits.monadCancelOps_
import cats.implicits.toFunctorOps
import cats.syntax.apply.*
import com.evolution.natseffect.Subscription
import io.nats.client.Subscription as JSubscription

private[natseffect] class WrappedSubscription[F[_]: Sync](wrapped: JSubscription, override val dispatcher: WrappedDispatcher[F])
    extends Subscription[F]
    with JavaWrapper[JSubscription] {

  override def subject: String = wrapped.getSubject

  override def queueName: Option[String] = Option(wrapped.getQueueName)

  override def unsubscribe: F[Unit] =
    (dispatcher.deleteSubscription(this) *> Sync[F].delay(wrapped.getDispatcher.unsubscribe(wrapped)).void).uncancelable

  override def unsubscribe(after: Int): F[Unit] =
    (dispatcher.deleteSubscription(this) *> Sync[F].delay(wrapped.getDispatcher.unsubscribe(wrapped, after)).void).uncancelable

  override def asJava: JSubscription = wrapped

}
