package com.evolution.natseffect.jetstream

import io.nats.client.api.{KeyValueConfiguration, KeyValueStatus}

/** Key-Value management context for administering key-value buckets.
  *
  * <p>KeyValueManagement provides methods for creating, updating, deleting, and querying key-value buckets. Each bucket is backed by a
  * JetStream stream and can have its own configuration for: <ul> <li>Storage type (memory or file) <li>Replication factor <li>History per
  * key <li>TTL for entries <li>Bucket limits </ul>
  *
  * <p>This trait wraps the Java NATS KeyValueManagement API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/key-value-store JetStream Key-Value Documentation]]
  */
trait KeyValueManagement[F[_]] {

  /** Create a new key-value bucket with the given configuration.
    *
    * @param config
    *   the bucket configuration
    * @return
    *   effect yielding KeyValueStatus for the created bucket, or failing with io.nats.client.JetStreamApiException if bucket already exists
    *   or configuration is invalid
    */
  def create(config: KeyValueConfiguration): F[KeyValueStatus]

  /** Update an existing key-value bucket with new configuration.
    *
    * @param config
    *   the updated bucket configuration
    * @return
    *   effect yielding KeyValueStatus for the updated bucket, or failing with io.nats.client.JetStreamApiException if bucket does not exist
    *   or update is invalid
    */
  def update(config: KeyValueConfiguration): F[KeyValueStatus]

  /** Get a list of all bucket names.
    *
    * @return
    *   effect yielding a vector of bucket names
    */
  def getBucketNames: F[Vector[String]]

  /** Get the status of a specific bucket.
    *
    * @param bucketName
    *   the name of the bucket
    * @return
    *   effect yielding KeyValueStatus with configuration and statistics, or failing with io.nats.client.JetStreamApiException if bucket
    *   does not exist
    */
  def getStatus(bucketName: String): F[KeyValueStatus]

  /** Get the status of all buckets.
    *
    * @return
    *   effect yielding a vector of KeyValueStatus objects
    */
  def getStatuses: F[Vector[KeyValueStatus]]

  /** Delete a key-value bucket and all its data.
    *
    * @param bucketName
    *   the name of the bucket to delete
    * @return
    *   effect that completes when bucket is deleted, or fails with io.nats.client.JetStreamApiException if bucket does not exist
    */
  def delete(bucketName: String): F[Unit]
}
