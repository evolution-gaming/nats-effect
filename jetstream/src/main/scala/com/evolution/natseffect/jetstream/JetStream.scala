package com.evolution.natseffect.jetstream

import cats.effect.Async
import com.evolution.natseffect.Connection
import com.evolution.natseffect.jetstream.impl.JetStreamImpl
import io.nats.client.{JetStreamOptions, KeyValueOptions}

/** JetStream is the main entry point for JetStream functionality, providing access to streams, consumers, and key-value stores.
  *
  * <p>This trait wraps the Java NATS JetStream API, providing a functional effect-based interface for Cats Effect applications.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream JetStream Documentation]]
  */
trait JetStream[F[_]] {

  /** Get a JetStream management context for administering streams and consumers.
    *
    * @param jetStreamOptions
    *   optional JetStream configuration options
    * @return
    *   effect yielding a JetStreamManagement instance
    */
  def jetStreamManagement(jetStreamOptions: Option[JetStreamOptions] = None): F[JetStreamManagement[F]]

  /** Get a JetStream publisher for publishing messages to streams.
    *
    * @param jetStreamOptions
    *   optional JetStream configuration options
    * @return
    *   effect yielding a JetStreamPublisher instance
    */
  def jetStreamPublisher(jetStreamOptions: Option[JetStreamOptions] = None): F[JetStreamPublisher[F]]

  /** Get a stream context for a specific stream, providing operations on that stream and its consumers.
    *
    * @param streamName
    *   the name of the stream
    * @param jetStreamOptions
    *   optional JetStream configuration options
    * @return
    *   effect yielding a StreamContext instance
    */
  def streamContext(streamName: String, jetStreamOptions: Option[JetStreamOptions] = None): F[StreamContext[F]]

  /** Get a key-value store for a specific bucket.
    *
    * @param bucketName
    *   the name of the bucket
    * @param keyValueOptions
    *   optional key-value configuration options
    * @return
    *   effect yielding a KeyValue instance
    */
  def keyValue(bucketName: String, keyValueOptions: Option[KeyValueOptions] = None): F[KeyValue[F]]

  /** Get a key-value management context for administering key-value buckets.
    *
    * @param keyValueOptions
    *   optional key-value configuration options
    * @return
    *   effect yielding a KeyValueManagement instance
    */
  def keyValueManagement(keyValueOptions: Option[KeyValueOptions] = None): F[KeyValueManagement[F]]
}

object JetStream {

  /** Create a JetStream context from an existing NATS connection.
    *
    * @param connection
    *   an existing NATS connection
    * @return
    *   effect yielding a JetStream instance
    */
  def fromConnection[F[_]: Async](connection: Connection[F]): F[JetStream[F]] =
    Async[F].delay {
      val wrappedConnection = connection match {
        case wrapped: com.evolution.natseffect.impl.WrappedConnection[F] => wrapped
        case _ => throw new IllegalArgumentException("Connection is not a WrappedConnection")
      }
      new JetStreamImpl[F](wrappedConnection)
    }

}
