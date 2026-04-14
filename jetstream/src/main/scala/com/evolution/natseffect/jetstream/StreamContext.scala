package com.evolution.natseffect.jetstream

import io.nats.client.PurgeOptions
import io.nats.client.api.{ConsumerConfiguration, MessageInfo, OrderedConsumerConfiguration, PurgeResponse, StreamInfoOptions}

/** Stream context providing operations on a specific JetStream stream and its consumers.
  *
  * <p>A StreamContext is bound to a specific stream and provides methods for: <ul> <li>Querying stream information and state <li>Purging
  * messages from the stream <li>Creating and managing consumers <li>Retrieving specific messages by sequence or subject <li>Deleting
  * individual messages </ul>
  *
  * <p>StreamContext is the primary interface for interacting with a stream's consumers. It provides factory methods for creating both
  * durable and ordered consumers, as well as methods for listing and deleting consumers.
  *
  * <p>This trait wraps the Java NATS StreamContext API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/streams JetStream Streams Documentation]]
  */
trait StreamContext[F[_]] {

  /** Get the name of this stream.
    *
    * @return
    *   the stream name
    */
  def getStreamName: String

  /** Get detailed information about this stream including configuration and current state.
    *
    * @param options
    *   optional request options (e.g., subject filter, deleted details)
    * @return
    *   effect yielding StreamInfo with configuration, state, and statistics
    */
  def getStreamInfo(options: Option[StreamInfoOptions] = None): F[StreamInfo]

  /** Purge messages from this stream based on options.
    *
    * @param options
    *   optional purge options (sequence, subject filter, keep count)
    * @return
    *   effect yielding PurgeResponse indicating whether purge was successful and number of messages purged
    */
  def purge(options: Option[PurgeOptions] = None): F[PurgeResponse]

  /** Get a consumer context for an existing consumer on this stream.
    *
    * @param consumerName
    *   the name of the consumer
    * @return
    *   effect yielding a ConsumerContext for the named consumer
    */
  def getConsumerContext(consumerName: String): F[ConsumerContext[F]]

  /** Create a new durable consumer or update an existing one with the given configuration.
    *
    * @param config
    *   the consumer configuration (must have durable name set)
    * @return
    *   effect yielding a ConsumerContext for the created/updated consumer
    */
  def createOrUpdateConsumer(config: ConsumerConfiguration): F[ConsumerContext[F]]

  /** Create an ordered consumer with the given configuration. Ordered consumers guarantee message order and automatically handle
    * redeliveries.
    *
    * @param config
    *   the ordered consumer configuration
    * @return
    *   effect yielding an OrderedConsumerContext
    */
  def createOrderedConsumer(config: OrderedConsumerConfiguration): F[OrderedConsumerContext[F]]

  /** Delete a consumer from this stream.
    *
    * @param consumerName
    *   the name of the consumer to delete
    * @return
    *   effect yielding true if deleted, false if consumer did not exist
    */
  def deleteConsumer(consumerName: String): F[Boolean]

  /** Get a list of all consumer names on this stream.
    *
    * @return
    *   effect yielding a vector of consumer names
    */
  def getConsumerNames: F[Vector[String]]

  /** Get detailed information about all consumers on this stream.
    *
    * @return
    *   effect yielding a vector of ConsumerInfo objects
    */
  def getConsumers: F[Vector[ConsumerInfo]]

  /** Get a specific message from the stream by sequence number.
    *
    * @param seq
    *   the sequence number of the message
    * @return
    *   effect yielding MessageInfo for the requested message, or failing with io.nats.client.JetStreamApiException if message not found
    */
  def getMessage(seq: Long): F[MessageInfo]

  /** Get the last message published to a subject in this stream.
    *
    * @param subject
    *   the subject to query
    * @return
    *   effect yielding MessageInfo for the last message on that subject, or failing with io.nats.client.JetStreamApiException if no
    *   messages found
    */
  def getLastMessage(subject: String): F[MessageInfo]

  /** Get the first message published to a subject in this stream.
    *
    * @param subject
    *   the subject to query
    * @return
    *   effect yielding MessageInfo for the first message on that subject, or failing with io.nats.client.JetStreamApiException if no
    *   messages found
    */
  def getFirstMessage(subject: String): F[MessageInfo]

  /** Get the next message after the given sequence on a subject.
    *
    * @param seq
    *   the sequence number to start from
    * @param subject
    *   the subject to query
    * @return
    *   effect yielding MessageInfo for the next message on that subject, or failing with io.nats.client.JetStreamApiException if no
    *   messages found
    */
  def getNextMessage(seq: Long, subject: String): F[MessageInfo]

  /** Delete a specific message from the stream by sequence number.
    *
    * @param seq
    *   the sequence number of the message to delete
    * @return
    *   effect yielding true if deleted, false otherwise
    */
  def deleteMessage(seq: Long): F[Boolean]

  /** Delete a specific message from the stream by sequence number.
    *
    * @param seq
    *   the sequence number of the message to delete
    * @param erase
    *   if true, securely erase the message; if false, mark as deleted
    * @return
    *   effect yielding true if deleted, false otherwise
    */
  def deleteMessage(seq: Long, erase: Boolean): F[Boolean]

}
