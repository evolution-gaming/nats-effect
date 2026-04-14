package com.evolution.natseffect.jetstream

import cats.Monad
import cats.effect.implicits.*
import cats.effect.{Async, Ref, Resource}
import cats.implicits.*
import com.evolution.natseffect.jetstream.KeyValueReader.*
import io.nats.client.KeyValueOptions
import io.nats.client.api.{KeyValueEntry, KeyValueOperation}

import scala.concurrent.duration.FiniteDuration

/** An abstraction over the JetStream key-value store that provides cached value access.
  *
  * <p>KeyValueReader maintains an in-memory cache of key-value entries, kept up-to-date through a watch subscription. This provides: <ul>
  * <li>Fast local access to values without server round-trips <li>Automatic cache updates as values change <li>Support for key filtering to
  * limit cached keys <li>Warmup phase to ensure cache is populated before use </ul>
  *
  * <p>Use cases: <ul> <li>Configuration management: Cache frequently accessed config keys <li>Feature flags: Fast local lookups
  * <li>Reference data: Cache relatively static data that changes occasionally </ul>
  *
  * <p>The reader is created as a Resource, which manages the watch subscription lifecycle. The resource acquisition blocks until the cache
  * is warmed up (or timeout is reached).
  */
trait KeyValueReader[F[_]] {

  // TODO: introduce ValueOf-like codec?

  /** Get the value for a key from the cache.
    *
    * @param key
    *   the key to retrieve
    * @return
    *   effect yielding Some(value) if key exists in cache, None otherwise
    */
  def get(key: Key): F[Option[Value]]

  /** Get all keys currently in the cache.
    *
    * @return
    *   effect yielding the set of cached keys
    */
  def keys: F[Set[Key]]

}

object KeyValueReader {

  type Key        = String
  type Value      = Array[Byte]
  type KeyFilters = Set[String]

  private[jetstream] class Impl[F[_]: Monad](
    // TODO: placeholder, replace with actual cache implementation like scache;
    //       we also assume that the value is not null, code that fills the cache should ensure that
    cache: Ref[F, Map[Key, KeyValueEntry]],
    val keyFilters: KeyFilters
  ) extends KeyValueReader[F] {

    override def get(key: Key): F[Option[Value]] =
      cache.get.map(_.get(key).map(_.getValue))

    override def keys: F[Set[Key]] =
      cache.get.map(_.keySet)
  }

  private[jetstream] def impl[F[_]: Async](
    kv: KeyValue[F],
    keyFilters: KeyFilters = Set.empty,
    warmupTimeout: FiniteDuration
  ): Resource[F, Impl[F]] = for {
    cache <- Ref[F].of(Map.empty[Key, KeyValueEntry]).toResource

    handler = (kvEntry: KeyValueEntry) =>
      kvEntry.getOperation match {
        // TODO: keep revision check? It's only needed in case of concurrent queue pulls, otherwise we rely on the
        //      underlying ordered consumer
        case KeyValueOperation.PUT =>
          cache.update(_.updatedWith(kvEntry.getKey) {
            case Some(oldValue) => Some(if (kvEntry.getRevision > oldValue.getRevision) kvEntry else oldValue)
            case None           => Some(kvEntry)
          })
        case KeyValueOperation.DELETE | KeyValueOperation.PURGE =>
          cache.update(_.updatedWith(kvEntry.getKey) {
            case Some(oldValue) => if (kvEntry.getRevision > oldValue.getRevision) None else Some(oldValue)
            case None           => None
          })
      }

    kvSubscription <- kv.watch(
      keys = keyFilters.toList,
      watchMode = KvWatchMode.LatestValues,
      handler = handler,
      warmupTimeout = warmupTimeout
    )

    _ <- kvSubscription.warmupLatch.get.toResource

  } yield new Impl(cache, keyFilters)

  /** Create a KeyValueReader for a specific bucket with optional key filtering.
    *
    * <p>The returned Resource blocks during acquisition until the cache is warmed up (all matching keys are loaded) or the warmupTimeout is
    * reached. Once the resource is acquired, the cache is kept up-to-date automatically through a watch subscription.
    *
    * @param js
    *   the JetStream context
    * @param bucketName
    *   the name of the key-value bucket
    * @param keyFilters
    *   set of wildcard filters to limit cached keys (e.g., Set("config.*", "feature.*"))
    * @param warmupTimeout
    *   maximum time to wait for cache warmup
    * @param keyValueOptions
    *   optional key-value configuration options
    * @return
    *   Resource yielding a KeyValueReader with populated cache
    */
  def make[F[_]: Async](
    js: JetStream[F],
    bucketName: String,
    keyFilters: KeyFilters,
    warmupTimeout: FiniteDuration,
    keyValueOptions: Option[KeyValueOptions] = None
  ): Resource[F, KeyValueReader[F]] =
    js
      .keyValue(bucketName, keyValueOptions)
      .toResource
      .flatMap(impl(_, keyFilters, warmupTimeout))

}
