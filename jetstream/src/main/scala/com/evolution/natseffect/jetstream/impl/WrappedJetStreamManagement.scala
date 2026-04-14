package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import cats.implicits.toFunctorOps
import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.{AccountStatistics, JetStreamManagement, StreamInfo}
import io.nats.client.api.StreamConfiguration

import scala.jdk.CollectionConverters.*

private[natseffect] class WrappedJetStreamManagement[F[_]: Async](
  wrapped: JJetStreamManagement
) extends JetStreamManagement[F]
    with JavaWrapper[JJetStreamManagement] {

  override def getAccountStatistics: F[AccountStatistics] =
    Async[F].blocking(new WrappedAccountStatistics(wrapped.getAccountStatistics))

  override def addStream(config: StreamConfiguration): F[StreamInfo] =
    Async[F].blocking(new WrappedStreamInfo(wrapped.addStream(config)))

  override def updateStream(config: StreamConfiguration): F[StreamInfo] =
    Async[F].blocking(new WrappedStreamInfo(wrapped.updateStream(config)))

  override def deleteStream(streamName: String): F[Boolean] =
    Async[F].blocking(wrapped.deleteStream(streamName))

  override def getStreamNames(subjectFilter: Option[String]): F[Vector[String]] =
    Async[F]
      .blocking(wrapped.getStreamNames(subjectFilter.orNull))
      .map(_.asScala.toVector)

  override def getStreams(subjectFilter: Option[String]): F[Vector[StreamInfo]] =
    Async[F]
      .blocking(wrapped.getStreams(subjectFilter.orNull))
      .map(_.asScala.toVector.map(new WrappedStreamInfo(_)))

  override def asJava: JJetStreamManagement = wrapped
}
