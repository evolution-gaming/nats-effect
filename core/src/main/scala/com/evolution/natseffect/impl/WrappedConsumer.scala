package com.evolution.natseffect.impl

import cats.effect.Sync
import com.evolution.natseffect.Consumer

private[natseffect] class WrappedConsumer[F[_]: Sync](wrapped: JConsumer) extends Consumer[F] with JavaWrapper[JConsumer] {

  override def setPendingLimits(maxMessages: Long, maxBytes: Long): F[Unit] =
    Sync[F].delay(wrapped.setPendingLimits(maxMessages, maxBytes))

  override def pendingMessageLimit: F[Long] = Sync[F].delay(wrapped.getPendingMessageLimit)

  override def pendingByteLimit: F[Long] = Sync[F].delay(wrapped.getPendingByteLimit)

  override def pendingMessageCount: F[Long] = Sync[F].delay(wrapped.getPendingMessageCount)

  override def pendingByteCount: F[Long] = Sync[F].delay(wrapped.getPendingByteCount)

  override def deliveredCount: F[Long] = Sync[F].delay(wrapped.getDeliveredCount)

  override def droppedCount: F[Long] = Sync[F].delay(wrapped.getDroppedCount)

  override def clearDroppedCount(): F[Unit] = Sync[F].delay(wrapped.clearDroppedCount())

  override def asJava: JConsumer = wrapped
}
