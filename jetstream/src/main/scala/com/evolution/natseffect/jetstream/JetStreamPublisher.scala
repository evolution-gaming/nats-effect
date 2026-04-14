package com.evolution.natseffect.jetstream

import com.evolution.natseffect.Message
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers

/** Publisher for sending messages to JetStream streams with guaranteed delivery and acknowledgment.
  *
  * <p>JetStreamPublisher provides methods for publishing messages to JetStream-enabled subjects. Unlike core NATS publishing, JetStream
  * publishes are acknowledged by the server, providing guarantees that the message was persisted to the stream.
  *
  * <p>The publisher supports: <ul> <li>Publishing with or without headers <li>Publishing with custom publish options (expected last message
  * ID, expected stream name, etc.) <li>Message deduplication using message IDs <li>Expected stream validation </ul>
  *
  * <p>All publish methods return a `PublishAck` which contains: <ul> <li>The stream name <li>The sequence number of the persisted message
  * <li>Whether the message was a duplicate <li>Domain if using JetStream domains </ul>
  *
  * <p>This trait wraps the Java NATS JetStream publishing API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/publish JetStream Publishing Documentation]]
  */
trait JetStreamPublisher[F[_]] {

  /** Publish a message to a JetStream stream.
    *
    * @param subject
    *   the subject to publish to
    * @param body
    *   the message body (will not be copied)
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(subject: String, body: Array[Byte]): F[PublishAck]

  /** Publish a message with headers to a JetStream stream.
    *
    * @param subject
    *   the subject to publish to
    * @param headers
    *   message headers
    * @param body
    *   the message body (will not be copied)
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(subject: String, headers: Headers, body: Array[Byte]): F[PublishAck]

  /** Publish a message with publish options to a JetStream stream.
    *
    * @param subject
    *   the subject to publish to
    * @param body
    *   the message body (will not be copied)
    * @param options
    *   publish options for expected stream, message ID, etc.
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(subject: String, body: Array[Byte], options: PublishOptions): F[PublishAck]

  /** Publish a message with headers and publish options to a JetStream stream.
    *
    * @param subject
    *   the subject to publish to
    * @param headers
    *   message headers
    * @param body
    *   the message body (will not be copied)
    * @param options
    *   publish options for expected stream, message ID, etc.
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(subject: String, headers: Headers, body: Array[Byte], options: PublishOptions): F[PublishAck]

  /** Publish an existing Message object to a JetStream stream.
    *
    * @param message
    *   the message to publish
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(message: Message): F[PublishAck]

  /** Publish an existing Message object with publish options to a JetStream stream.
    *
    * @param message
    *   the message to publish
    * @param options
    *   publish options for expected stream, message ID, etc.
    * @return
    *   effect yielding a PublishAck with stream name and sequence number
    */
  def publish(message: Message, options: PublishOptions): F[PublishAck]

}
