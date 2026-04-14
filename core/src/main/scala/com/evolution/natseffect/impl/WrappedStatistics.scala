package com.evolution.natseffect.impl

import cats.effect.Sync
import com.evolution.natseffect.Statistics

private[natseffect] class WrappedStatistics[F[_]: Sync](wrapped: JStatistics) extends Statistics[F] with JavaWrapper[JStatistics] {

  override def pings: F[Long] = Sync[F].delay(wrapped.getPings)

  override def reconnects: F[Long] = Sync[F].delay(wrapped.getReconnects)

  override def droppedCount: F[Long] = Sync[F].delay(wrapped.getDroppedCount)

  override def OKs: F[Long] = Sync[F].delay(wrapped.getOKs)

  override def errs: F[Long] = Sync[F].delay(wrapped.getErrs)

  override def exceptions: F[Long] = Sync[F].delay(wrapped.getExceptions)

  override def requestsSent: F[Long] = Sync[F].delay(wrapped.getRequestsSent)

  override def repliesReceived: F[Long] = Sync[F].delay(wrapped.getRepliesReceived)

  override def duplicateRepliesReceived: F[Long] = Sync[F].delay(wrapped.getDuplicateRepliesReceived)

  override def orphanRepliesReceived: F[Long] = Sync[F].delay(wrapped.getOrphanRepliesReceived)

  override def inMsgs: F[Long] = Sync[F].delay(wrapped.getInMsgs)

  override def outMsgs: F[Long] = Sync[F].delay(wrapped.getOutMsgs)

  override def inBytes: F[Long] = Sync[F].delay(wrapped.getInBytes)

  override def outBytes: F[Long] = Sync[F].delay(wrapped.getOutBytes)

  override def flushCounter: F[Long] = Sync[F].delay(wrapped.getFlushCounter)

  override def outstandingRequests: F[Long] = Sync[F].delay(wrapped.getOutstandingRequests)

  override def asJava: JStatistics = wrapped
}
