package com.evolution.natseffect.jetstream

import cats.effect.Resource
import cats.effect.std.QueueSource
import io.nats.client.MessageTtl
import io.nats.client.api.{KeyValueEntry, KeyValuePurgeOptions, KeyValueStatus}

import scala.concurrent.duration.FiniteDuration

/** Key-Value store providing distributed key-value storage with history and watch capabilities.
  *
  * <p>JetStream Key-Value stores are built on top of JetStream streams, providing a familiar key-value interface with the benefits of
  * JetStream persistence, replication, and replay. Each bucket is backed by a stream where keys are subjects and values are message
  * payloads.
  *
  * <p>Features: <ul> <li>Put/Get/Delete operations with revision tracking <li>Conditional updates based on expected revision <li>History
  * access for each key <li>Watch capabilities for real-time updates <li>TTL support for automatic expiration <li>Wildcard key filtering
  * <li> Optimistic concurrency control </ul>
  *
  * <p>Revision semantics: Each put operation increases the revision number. You can: <ul> <li>Get a specific revision of a key <li>
  * Conditionally update based on expected revision <li>Create a key only if it doesn't exist <li>View the full history of a key </ul>
  *
  * <p>This trait wraps the Java NATS KeyValue API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/key-value-store JetStream Key-Value Documentation]]
  */
trait KeyValue[F[_]] {

  /** Get the status of this key-value bucket including size, entry count, and configuration.
    *
    * @return
    *   effect yielding KeyValueStatus
    */
  def getStatus: F[KeyValueStatus]

  /** Get the name of this key-value bucket.
    *
    * @return
    *   the bucket name
    */
  def bucketName: String

  /** Get the latest value for a key.
    *
    * @param key
    *   the key to retrieve
    * @return
    *   effect yielding Some(KeyValueEntry) with value and revision if found, or None if key does not exist
    */
  def get(key: String): F[Option[KeyValueEntry]]

  /** Get a specific revision of a key.
    *
    * @param key
    *   the key to retrieve
    * @param revision
    *   the revision number
    * @return
    *   effect yielding Some(KeyValueEntry) at that revision, or None if key or revision does not exist
    */
  def get(key: String, revision: Long): F[Option[KeyValueEntry]]

  /** Put a value for a key, creating or updating it.
    *
    * @param key
    *   the key
    * @param value
    *   the value as bytes
    * @return
    *   effect yielding the revision number of the stored value
    */
  def put(key: String, value: Array[Byte]): F[Long]

  /** Put a string value for a key, creating or updating it.
    *
    * @param key
    *   the key
    * @param value
    *   the value as string
    * @return
    *   effect yielding the revision number of the stored value
    */
  def put(key: String, value: String): F[Long]

  /** Put a numeric value for a key, creating or updating it.
    *
    * @param key
    *   the key
    * @param value
    *   the value as number
    * @return
    *   effect yielding the revision number of the stored value
    */
  def put(key: String, value: Number): F[Long]

  /** Create a new key with a value. Fails if the key already exists.
    *
    * @param key
    *   the key
    * @param value
    *   the value as bytes
    * @return
    *   effect yielding the revision number (always 1 for new keys), or failing with io.nats.client.JetStreamApiException if key already
    *   exists
    */
  def create(key: String, value: Array[Byte]): F[Long]

  /** Create a new key with a value and TTL. Fails if the key already exists.
    *
    * @param key
    *   the key
    * @param value
    *   the value as bytes
    * @param messageTtl
    *   time-to-live for the key
    * @return
    *   effect yielding the revision number (always 1 for new keys), or failing with io.nats.client.JetStreamApiException if key already
    *   exists
    */
  def create(key: String, value: Array[Byte], messageTtl: MessageTtl): F[Long]

  /** Update a key's value conditionally based on expected revision (optimistic concurrency control).
    *
    * @param key
    *   the key
    * @param value
    *   the new value as bytes
    * @param expectedRevision
    *   the expected current revision
    * @return
    *   effect yielding the new revision number, or failing with io.nats.client.JetStreamApiException if revision mismatch
    */
  def update(key: String, value: Array[Byte], expectedRevision: Long): F[Long]

  /** Update a key's string value conditionally based on expected revision (optimistic concurrency control).
    *
    * @param key
    *   the key
    * @param value
    *   the new value as string
    * @param expectedRevision
    *   the expected current revision
    * @return
    *   effect yielding the new revision number, or failing with io.nats.client.JetStreamApiException if revision mismatch
    */
  def update(key: String, value: String, expectedRevision: Long): F[Long]

  /** Delete a key (soft delete - adds a delete marker, preserves history).
    *
    * @param key
    *   the key to delete
    * @return
    *   effect that completes when key is deleted
    */
  def delete(key: String): F[Unit]

  /** Delete a key conditionally based on expected revision.
    *
    * @param key
    *   the key to delete
    * @param expectedRevision
    *   the expected current revision
    * @return
    *   effect that completes when key is deleted, or fails with io.nats.client.JetStreamApiException if revision mismatch
    */
  def delete(key: String, expectedRevision: Long): F[Unit]

  /** Purge a key (hard delete - removes all history).
    *
    * @param key
    *   the key to purge
    * @return
    *   effect that completes when key is purged
    */
  def purge(key: String): F[Unit]

  /** Purge a key conditionally based on expected revision.
    *
    * @param key
    *   the key to purge
    * @param expectedRevision
    *   the expected current revision
    * @return
    *   effect that completes when key is purged, or fails with io.nats.client.JetStreamApiException if revision mismatch
    */
  def purge(key: String, expectedRevision: Long): F[Unit]

  /** Purge a key with TTL.
    *
    * @param key
    *   the key to purge
    * @param messageTtl
    *   time-to-live for the purge marker
    * @return
    *   effect that completes when key is purged
    */
  def purge(key: String, messageTtl: MessageTtl): F[Unit]

  /** Purge a key with TTL and expected revision.
    *
    * @param key
    *   the key to purge
    * @param expectedRevision
    *   the expected current revision
    * @param messageTtl
    *   time-to-live for the purge marker
    * @return
    *   effect that completes when key is purged, or fails with io.nats.client.JetStreamApiException if revision mismatch
    */
  def purge(key: String, expectedRevision: Long, messageTtl: MessageTtl): F[Unit]

  /** Purge all delete markers from the bucket.
    *
    * @return
    *   effect that completes when delete markers are purged
    */
  def purgeDeletes: F[Unit]

  /** Purge delete markers with options.
    *
    * @param options
    *   purge options
    * @return
    *   effect that completes when delete markers are purged
    */
  def purgeDeletes(options: KeyValuePurgeOptions): F[Unit]

  /** Watch all keys in the bucket for changes.
    *
    * @param watchMode
    *   watch mode (new only, include history, updates only)
    * @param handler
    *   function to handle each key change
    * @param warmupTimeout
    *   timeout for initial warmup
    * @param metaDataOnly
    *   if true, only metadata is included (no value)
    * @return
    *   Resource managing the watch subscription
    */
  def watchAll(
    watchMode: KvWatchMode,
    handler: KeyValueEntry => F[Unit],
    warmupTimeout: FiniteDuration,
    metaDataOnly: Boolean = false
  ): Resource[F, SubscriptionWithWarmup[F]]

  /** Watch specific keys for changes.
    *
    * @param keys
    *   list of keys to watch (supports wildcards)
    * @param watchMode
    *   watch mode (new only, include history, updates only)
    * @param handler
    *   function to handle each key change
    * @param warmupTimeout
    *   timeout for initial warmup
    * @param metaDataOnly
    *   if true, only metadata is included (no value)
    * @return
    *   Resource managing the watch subscription
    */
  def watch(
    keys: List[String],
    watchMode: KvWatchMode,
    handler: KeyValueEntry => F[Unit],
    warmupTimeout: FiniteDuration,
    metaDataOnly: Boolean = false
  ): Resource[F, SubscriptionWithWarmup[F]]

  /** Get all keys in the bucket.
    *
    * @param timeout
    *   timeout for the operation
    * @return
    *   effect yielding list of keys
    */
  def keys(timeout: FiniteDuration): F[List[String]]

  /** Get keys matching a filter.
    *
    * @param filter
    *   wildcard filter (e.g., "orders.*")
    * @param timeout
    *   timeout for the operation
    * @return
    *   effect yielding list of matching keys
    */
  def keys(filter: String, timeout: FiniteDuration): F[List[String]]

  /** Get keys matching multiple filters.
    *
    * @param filters
    *   list of wildcard filters
    * @param timeout
    *   timeout for the operation
    * @return
    *   effect yielding list of matching keys
    */
  def keys(filters: List[String], timeout: FiniteDuration): F[List[String]]

  /** Consume all keys from a queue source.
    *
    * @param queueCapacity
    *   optional queue capacity
    * @param timeout
    *   timeout for the operation
    * @return
    *   Resource managing a QueueSource of keys
    */
  def consumeKeys(queueCapacity: Option[Int], timeout: FiniteDuration): Resource[F, QueueSource[F, Option[String]]]

  /** Consume keys matching a filter from a queue source.
    *
    * @param filter
    *   wildcard filter
    * @param queueCapacity
    *   optional queue capacity
    * @param timeout
    *   timeout for the operation
    * @return
    *   Resource managing a QueueSource of keys
    */
  def consumeKeys(filter: String, queueCapacity: Option[Int], timeout: FiniteDuration): Resource[F, QueueSource[F, Option[String]]]

  /** Consume keys matching multiple filters from a queue source.
    *
    * @param filters
    *   list of wildcard filters
    * @param queueCapacity
    *   optional queue capacity
    * @param timeout
    *   timeout for the operation
    * @return
    *   Resource managing a QueueSource of keys
    */
  def consumeKeys(filters: List[String], queueCapacity: Option[Int], timeout: FiniteDuration): Resource[F, QueueSource[F, Option[String]]]

  /** Get the full history of a key.
    *
    * @param key
    *   the key
    * @param timeout
    *   timeout for the operation
    * @return
    *   effect yielding list of KeyValueEntry representing the history
    */
  def history(key: String, timeout: FiniteDuration): F[List[KeyValueEntry]]
}
