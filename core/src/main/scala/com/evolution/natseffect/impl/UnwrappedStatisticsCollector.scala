package com.evolution.natseffect.impl

import cats.Applicative
import cats.syntax.all.*
import com.evolution.natseffect.StatisticsCollector

private[natseffect] class UnwrappedStatisticsCollector[F[_]: Applicative](
  val statisticsCollector: StatisticsCollector[F],
  effectRunner: EffectRunner[F]
) extends JStatisticsCollector {
  override def setAdvancedTracking(trackAdvanced: Boolean): Unit = ()

  override def incrementPingCount(): Unit = effectRunner.run(statisticsCollector.incrementPingCount)

  override def incrementReconnects(): Unit = effectRunner.run(statisticsCollector.incrementReconnects)

  override def incrementDroppedCount(): Unit = effectRunner.run(statisticsCollector.incrementDroppedCount)

  override def incrementOkCount(): Unit = effectRunner.run(statisticsCollector.incrementOkCount)

  override def incrementErrCount(): Unit = effectRunner.run(statisticsCollector.incrementErrCount)

  override def incrementExceptionCount(): Unit = effectRunner.run(statisticsCollector.incrementExceptionCount)

  override def incrementRequestsSent(): Unit = effectRunner.run(statisticsCollector.incrementRequestsSent)

  override def incrementRepliesReceived(): Unit = effectRunner.run(statisticsCollector.incrementRepliesReceived)

  override def incrementDuplicateRepliesReceived(): Unit =
    effectRunner.run(statisticsCollector.incrementDuplicateRepliesReceived)

  override def incrementOrphanRepliesReceived(): Unit =
    effectRunner.run(statisticsCollector.incrementOrphanRepliesReceived)

  override def incrementInMsgs(): Unit = effectRunner.run(statisticsCollector.incrementInMsgs)

  override def incrementOutMsgs(): Unit = effectRunner.run(statisticsCollector.incrementOutMsgs)

  override def incrementInBytes(bytes: Long): Unit = effectRunner.run(statisticsCollector.incrementInBytes(bytes))

  override def incrementOutBytes(bytes: Long): Unit = effectRunner.run(statisticsCollector.incrementOutBytes(bytes))

  override def incrementIn(bytes: Long): Unit =
    effectRunner.run(statisticsCollector.incrementInMsgs *> statisticsCollector.incrementInBytes(bytes))

  override def incrementOut(bytes: Long): Unit =
    effectRunner.run(statisticsCollector.incrementOutMsgs *> statisticsCollector.incrementOutBytes(bytes))

  override def incrementFlushCounter(): Unit = effectRunner.run(statisticsCollector.incrementFlushCounter)

  override def incrementOutstandingRequests(): Unit =
    effectRunner.run(statisticsCollector.incrementOutstandingRequests)

  override def decrementOutstandingRequests(): Unit =
    effectRunner.run(statisticsCollector.decrementOutstandingRequests)

  override def registerRead(bytes: Long): Unit = effectRunner.run(statisticsCollector.registerRead(bytes))

  override def registerWrite(bytes: Long): Unit = effectRunner.run(statisticsCollector.registerWrite(bytes))

  override def getPings: Long = effectRunner.run(statisticsCollector.pings)

  override def getReconnects: Long = effectRunner.run(statisticsCollector.reconnects)

  override def getDroppedCount: Long = effectRunner.run(statisticsCollector.droppedCount)

  override def getOKs: Long = effectRunner.run(statisticsCollector.OKs)

  override def getErrs: Long = effectRunner.run(statisticsCollector.errs)

  override def getExceptions: Long = effectRunner.run(statisticsCollector.exceptions)

  override def getRequestsSent: Long = effectRunner.run(statisticsCollector.requestsSent)

  override def getRepliesReceived: Long = effectRunner.run(statisticsCollector.repliesReceived)

  override def getDuplicateRepliesReceived: Long = effectRunner.run(statisticsCollector.duplicateRepliesReceived)

  override def getOrphanRepliesReceived: Long = effectRunner.run(statisticsCollector.orphanRepliesReceived)

  override def getInMsgs: Long = effectRunner.run(statisticsCollector.inMsgs)

  override def getOutMsgs: Long = effectRunner.run(statisticsCollector.outMsgs)

  override def getInBytes: Long = effectRunner.run(statisticsCollector.inBytes)

  override def getOutBytes: Long = effectRunner.run(statisticsCollector.outBytes)

  override def getFlushCounter: Long = effectRunner.run(statisticsCollector.flushCounter)

  override def getOutstandingRequests: Long = effectRunner.run(statisticsCollector.outstandingRequests)
}
