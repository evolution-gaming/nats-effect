package com.evolution.natseffect

trait StatisticsCollector[F[_]] extends Statistics[F] {

  def incrementPingCount: F[Unit]

  def incrementReconnects: F[Unit]

  def incrementDroppedCount: F[Unit]

  def incrementOkCount: F[Unit]

  def incrementErrCount: F[Unit]

  def incrementExceptionCount: F[Unit]

  def incrementRequestsSent: F[Unit]

  def incrementRepliesReceived: F[Unit]

  def incrementDuplicateRepliesReceived: F[Unit]

  def incrementOrphanRepliesReceived: F[Unit]

  def incrementInMsgs: F[Unit]

  def incrementOutMsgs: F[Unit]

  def incrementInBytes(bytes: Long): F[Unit]

  def incrementOutBytes(bytes: Long): F[Unit]

  def incrementFlushCounter: F[Unit]

  def incrementOutstandingRequests: F[Unit]

  def decrementOutstandingRequests: F[Unit]

  def registerRead(bytes: Long): F[Unit]

  def registerWrite(bytes: Long): F[Unit]

}

object StatisticsCollector {
  trait Multicluster[F[_]] {
    def forCluster(cluster: String): StatisticsCollector[F]
  }
}
