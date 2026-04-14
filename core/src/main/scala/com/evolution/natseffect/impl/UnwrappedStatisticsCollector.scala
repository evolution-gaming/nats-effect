package com.evolution.natseffect.impl

import cats.effect.std.Dispatcher
import com.evolution.natseffect.StatisticsCollector

private[natseffect] class UnwrappedStatisticsCollector[F[_]](val statisticsCollector: StatisticsCollector[F], dispatcher: Dispatcher[F])
    extends JStatisticsCollector {
  override def setAdvancedTracking(trackAdvanced: Boolean): Unit = ()

  override def incrementPingCount(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementPingCount)

  override def incrementReconnects(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementReconnects)

  override def incrementDroppedCount(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementDroppedCount)

  override def incrementOkCount(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementOkCount)

  override def incrementErrCount(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementErrCount)

  override def incrementExceptionCount(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementExceptionCount)

  override def incrementRequestsSent(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementRequestsSent)

  override def incrementRepliesReceived(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementRepliesReceived)

  override def incrementDuplicateRepliesReceived(): Unit =
    dispatcher.unsafeRunAndForget(statisticsCollector.incrementDuplicateRepliesReceived)

  override def incrementOrphanRepliesReceived(): Unit =
    dispatcher.unsafeRunAndForget(statisticsCollector.incrementOrphanRepliesReceived)

  override def incrementInMsgs(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementInMsgs)

  override def incrementOutMsgs(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementOutMsgs)

  override def incrementInBytes(bytes: Long): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementInBytes(bytes))

  override def incrementOutBytes(bytes: Long): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementOutBytes(bytes))

  override def incrementFlushCounter(): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.incrementFlushCounter)

  override def incrementOutstandingRequests(): Unit =
    dispatcher.unsafeRunAndForget(statisticsCollector.incrementOutstandingRequests)

  override def decrementOutstandingRequests(): Unit =
    dispatcher.unsafeRunAndForget(statisticsCollector.decrementOutstandingRequests)

  override def registerRead(bytes: Long): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.registerRead(bytes))

  override def registerWrite(bytes: Long): Unit = dispatcher.unsafeRunAndForget(statisticsCollector.registerWrite(bytes))

  override def getPings: Long = dispatcher.unsafeRunSync(statisticsCollector.pings)

  override def getReconnects: Long = dispatcher.unsafeRunSync(statisticsCollector.reconnects)

  override def getDroppedCount: Long = dispatcher.unsafeRunSync(statisticsCollector.droppedCount)

  override def getOKs: Long = dispatcher.unsafeRunSync(statisticsCollector.OKs)

  override def getErrs: Long = dispatcher.unsafeRunSync(statisticsCollector.errs)

  override def getExceptions: Long = dispatcher.unsafeRunSync(statisticsCollector.exceptions)

  override def getRequestsSent: Long = dispatcher.unsafeRunSync(statisticsCollector.requestsSent)

  override def getRepliesReceived: Long = dispatcher.unsafeRunSync(statisticsCollector.repliesReceived)

  override def getDuplicateRepliesReceived: Long = dispatcher.unsafeRunSync(statisticsCollector.duplicateRepliesReceived)

  override def getOrphanRepliesReceived: Long = dispatcher.unsafeRunSync(statisticsCollector.orphanRepliesReceived)

  override def getInMsgs: Long = dispatcher.unsafeRunSync(statisticsCollector.inMsgs)

  override def getOutMsgs: Long = dispatcher.unsafeRunSync(statisticsCollector.outMsgs)

  override def getInBytes: Long = dispatcher.unsafeRunSync(statisticsCollector.inBytes)

  override def getOutBytes: Long = dispatcher.unsafeRunSync(statisticsCollector.outBytes)

  override def getFlushCounter: Long = dispatcher.unsafeRunSync(statisticsCollector.flushCounter)

  override def getOutstandingRequests: Long = dispatcher.unsafeRunSync(statisticsCollector.outstandingRequests)
}
