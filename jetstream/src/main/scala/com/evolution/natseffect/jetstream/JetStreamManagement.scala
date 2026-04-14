package com.evolution.natseffect.jetstream

import io.nats.client.api.StreamConfiguration

/** JetStream management context for administering streams and their configurations.
  *
  * <p>This interface provides methods for creating, updating, deleting, and querying JetStream streams. Streams are message stores that
  * capture and store messages published to specific subjects. Each stream defines various properties such as: <ul> <li>Which subjects it
  * listens to <li>How long messages are retained <li>How many messages are retained <li>Storage type (memory or file) <li>Replication
  * factor for clustering <li>Limits on message size, bytes, and age </ul>
  *
  * <p>This trait wraps the Java NATS JetStreamManagement API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/streams JetStream Streams Documentation]]
  */
trait JetStreamManagement[F[_]] {

  /** Get statistics about the current account's JetStream usage.
    *
    * @return
    *   effect yielding AccountStatistics with memory, storage, streams, and consumer counts
    */
  def getAccountStatistics: F[AccountStatistics]

  /** Create a new stream with the given configuration.
    *
    * @param config
    *   the stream configuration
    * @return
    *   effect yielding StreamInfo for the created stream, or failing with io.nats.client.JetStreamApiException if the stream already exists
    *   or configuration is invalid
    */
  def addStream(config: StreamConfiguration): F[StreamInfo]

  /** Update an existing stream with new configuration. Note that some properties cannot be changed after creation (e.g., storage type).
    *
    * @param config
    *   the updated stream configuration
    * @return
    *   effect yielding StreamInfo for the updated stream, or failing with io.nats.client.JetStreamApiException if the stream does not exist
    *   or update is invalid
    */
  def updateStream(config: StreamConfiguration): F[StreamInfo]

  /** Delete a stream and all its messages.
    *
    * @param streamName
    *   the name of the stream to delete
    * @return
    *   effect yielding true if deleted, false if stream did not exist
    */
  def deleteStream(streamName: String): F[Boolean]

  /** Get a list of all stream names, optionally filtered by subject.
    *
    * @param subjectFilter
    *   optional subject filter (e.g., "orders.*")
    * @return
    *   effect yielding a vector of stream names
    */
  def getStreamNames(subjectFilter: Option[String] = None): F[Vector[String]]

  /** Get detailed information about all streams, optionally filtered by subject.
    *
    * @param subjectFilter
    *   optional subject filter (e.g., "orders.*")
    * @return
    *   effect yielding a vector of StreamInfo objects
    */
  def getStreams(subjectFilter: Option[String] = None): F[Vector[StreamInfo]]

}
