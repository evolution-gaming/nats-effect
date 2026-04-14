package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.KeyValueManagement
import io.nats.client.api.{KeyValueConfiguration, KeyValueStatus}

import scala.jdk.CollectionConverters.*

private[natseffect] class WrappedKeyValueManagement[F[_]: Async](
  wrapped: JKeyValueManagement
) extends KeyValueManagement[F]
    with JavaWrapper[JKeyValueManagement] {

  override def create(config: KeyValueConfiguration): F[KeyValueStatus] =
    Async[F].blocking(wrapped.create(config))

  override def update(config: KeyValueConfiguration): F[KeyValueStatus] =
    Async[F].blocking(wrapped.update(config))

  override def getBucketNames: F[Vector[String]] =
    Async[F].blocking(wrapped.getBucketNames.asScala.toVector)

  override def getStatus(bucketName: String): F[KeyValueStatus] =
    Async[F].blocking(wrapped.getStatus(bucketName))

  override def getStatuses: F[Vector[KeyValueStatus]] =
    Async[F].blocking(wrapped.getStatuses.asScala.toVector)

  override def delete(bucketName: String): F[Unit] =
    Async[F].blocking(wrapped.delete(bucketName))

  override def asJava: JKeyValueManagement = wrapped
}
