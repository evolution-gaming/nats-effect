package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.impl.WrappedConnection
import com.evolution.natseffect.jetstream.*
import io.nats.client.{JetStreamOptions, KeyValueOptions}

private[natseffect] class JetStreamImpl[F[_]: Async](wrappedConnection: WrappedConnection[F]) extends JetStream[F] {

  override def jetStreamManagement(jetStreamOptions: Option[JetStreamOptions]): F[JetStreamManagement[F]] =
    Async[F].delay {
      new WrappedJetStreamManagement[F](wrappedConnection.asJava.jetStreamManagement(jetStreamOptions.orNull))
    }

  override def jetStreamPublisher(jetStreamOptions: Option[JetStreamOptions] = None): F[JetStreamPublisher[F]] =
    Async[F].delay {
      new WrappedJetStream[F](wrappedConnection.asJava.jetStream(jetStreamOptions.orNull))
    }

  override def streamContext(streamName: String, jetStreamOptions: Option[JetStreamOptions]): F[StreamContext[F]] =
    Async[F].delay {
      new WrappedStreamContext[F](
        wrappedConnection.asJava.getStreamContext(streamName, jetStreamOptions.orNull),
        wrappedConnection.asJava
      )
    }

  override def keyValue(bucketName: String, keyValueOptions: Option[KeyValueOptions] = None): F[KeyValue[F]] =
    Async[F].delay {
      new WrappedKeyValue[F](
        wrappedConnection.asJava.keyValue(bucketName, keyValueOptions.orNull),
        this,
        keyValueOptions
      )
    }

  override def keyValueManagement(keyValueOptions: Option[KeyValueOptions]): F[KeyValueManagement[F]] =
    Async[F].delay {
      new WrappedKeyValueManagement[F](wrappedConnection.asJava.keyValueManagement(keyValueOptions.orNull))
    }

}
