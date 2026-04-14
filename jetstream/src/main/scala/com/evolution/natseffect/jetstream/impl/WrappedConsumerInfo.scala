package com.evolution.natseffect.jetstream.impl

import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.ConsumerInfo
import io.nats.client.api.{ClusterInfo, ConsumerConfiguration, PriorityGroupState, SequenceInfo}

import java.time.ZonedDateTime
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.jdk.DurationConverters.JavaDurationOps

private[natseffect] class WrappedConsumerInfo(wrapped: JConsumerInfo) extends ConsumerInfo with JavaWrapper[JConsumerInfo] {
  override def consumerConfiguration: ConsumerConfiguration = wrapped.getConsumerConfiguration

  override def name: String = wrapped.getName

  override def streamName: String = wrapped.getStreamName

  override def creationTime: ZonedDateTime = wrapped.getCreationTime

  override def delivered: SequenceInfo = wrapped.getDelivered

  override def ackFloor: SequenceInfo = wrapped.getAckFloor

  override def numPending: Long = wrapped.getNumPending

  override def numWaiting: Long = wrapped.getNumWaiting

  override def numAckPending: Long = wrapped.getNumAckPending

  override def redelivered: Long = wrapped.getRedelivered

  override def paused: Boolean = wrapped.getPaused

  override def pauseRemaining: Option[FiniteDuration] = Option(wrapped.getPauseRemaining).map(_.toScala)

  override def clusterInfo: Option[ClusterInfo] = Option(wrapped.getClusterInfo)

  override def pushBound: Boolean = wrapped.isPushBound

  override def timestamp: Option[ZonedDateTime] = Option(wrapped.getTimestamp)

  override def priorityGroupStates: Option[List[PriorityGroupState]] =
    Option(wrapped.getPriorityGroupStates).map(_.asScala.toList)

  override def calculatedPending: Long = wrapped.getCalculatedPending

  override def asJava: JConsumerInfo = wrapped
}
