package com.evolution.natseffect.impl

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.evolution.natseffect.StatisticsCollector

private[natseffect] class UnwrappedStatisticsCollector[F[_]: Async](
  val statisticsCollector: StatisticsCollector[F],
  dispatcher: Dispatcher[F]
) extends JStatisticsCollector {
  override def setAdvancedTracking(trackAdvanced: Boolean): Unit = ()

  override def incrementPingCount(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementPingCount)

  override def incrementReconnects(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementReconnects)

  override def incrementDroppedCount(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementDroppedCount)

  override def incrementOkCount(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementOkCount)

  override def incrementErrCount(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementErrCount)

  override def incrementExceptionCount(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementExceptionCount)

  override def incrementRequestsSent(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementRequestsSent)

  override def incrementRepliesReceived(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementRepliesReceived)

  override def incrementDuplicateRepliesReceived(): Unit =
    dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementDuplicateRepliesReceived)

  override def incrementOrphanRepliesReceived(): Unit =
    dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementOrphanRepliesReceived)

  override def incrementInMsgs(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementInMsgs)

  override def incrementOutMsgs(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementOutMsgs)

  override def incrementInBytes(bytes: Long): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementInBytes(bytes))

  override def incrementOutBytes(bytes: Long): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementOutBytes(bytes))

  override def incrementFlushCounter(): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementFlushCounter)

  override def incrementOutstandingRequests(): Unit =
    dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.incrementOutstandingRequests)

  override def decrementOutstandingRequests(): Unit =
    dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.decrementOutstandingRequests)

  override def registerRead(bytes: Long): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.registerRead(bytes))

  override def registerWrite(bytes: Long): Unit = dispatcher.unsafeRunSyncNoForkVoid(statisticsCollector.registerWrite(bytes))

  override def getPings: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.pings)

  override def getReconnects: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.reconnects)

  override def getDroppedCount: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.droppedCount)

  override def getOKs: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.OKs)

  override def getErrs: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.errs)

  override def getExceptions: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.exceptions)

  override def getRequestsSent: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.requestsSent)

  override def getRepliesReceived: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.repliesReceived)

  override def getDuplicateRepliesReceived: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.duplicateRepliesReceived)

  override def getOrphanRepliesReceived: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.orphanRepliesReceived)

  override def getInMsgs: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.inMsgs)

  override def getOutMsgs: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.outMsgs)

  override def getInBytes: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.inBytes)

  override def getOutBytes: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.outBytes)

  override def getFlushCounter: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.flushCounter)

  override def getOutstandingRequests: Long = dispatcher.unsafeRunSyncNoFork(statisticsCollector.outstandingRequests)
}
