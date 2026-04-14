package com.evolution.natseffect.jetstream.impl

import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.StreamInfo
import io.nats.client.api.{ClusterInfo, MirrorInfo, SourceInfo, StreamAlternate, StreamConfiguration, StreamState}

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters.ListHasAsScala

private[natseffect] class WrappedStreamInfo(wrapped: JStreamInfo) extends StreamInfo with JavaWrapper[JStreamInfo] {

  override def config: StreamConfiguration = wrapped.getConfiguration

  override def streamState: StreamState = wrapped.getStreamState

  override def createTime: ZonedDateTime = wrapped.getCreateTime

  override def mirrorInfo: Option[MirrorInfo] = Option(wrapped.getMirrorInfo)

  override def sourceInfos: Option[List[SourceInfo]] = Option(wrapped.getSourceInfos).map(_.asScala.toList)

  override def clusterInfo: Option[ClusterInfo] = Option(wrapped.getClusterInfo)

  override def alternates: Option[List[StreamAlternate]] = Option(wrapped.getAlternates).map(_.asScala.toList)

  override def timestamp: Option[ZonedDateTime] = Option(wrapped.getTimestamp)

  override def asJava: JStreamInfo = wrapped
}
