package com.evolution.natseffect.jetstream

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.{Hotswap, Mutex}
import cats.implicits.*
import com.evolution.natseffect.jetstream.KeyValueReader.*

import scala.concurrent.duration.FiniteDuration

/** A KeyValueReader that supports dynamic filter updates without recreating the subscription.
  *
  * <p>DynamicKeyValueReader extends KeyValueReader with the ability to change the set of watched keys at runtime. When filters are updated:
  * <ul> <li>The existing watch subscription is closed <li>A new subscription is created with updated filters <li>The cache is rebuilt with
  * the new key set <li>Operations are thread-safe during filter updates </ul>
  *
  * @see
  *   [[KeyValueReader]]
  */
trait DynamicKeyValueReader[F[_]] extends KeyValueReader[F] {

  /** Update the key filters dynamically.
    *
    * <p>This method: <ul> <li>Acquires a lock to ensure thread-safe updates <li>Closes the existing watch subscription <li>Creates a new
    * watch with updated filters <li>Rebuilds the cache with the new key set </ul>
    *
    * While the update is running, calls to get and keys will continue to operate on the old cache until the new subscription is ready. Once
    * the update completes, subsequent calls will reflect the new filters.
    *
    * @param f
    *   function to transform the current filters into new filters
    * @return
    *   effect that completes when the filters are updated and cache is rebuilt
    */
  def updateFilters(f: KeyFilters => KeyFilters): F[Unit]
}

object DynamicKeyValueReader {

  private class Impl[F[_]: MonadCancelThrow](
    hotswap: Hotswap[F, KeyValueReader.Impl[F]],
    createReader: KeyFilters => Resource[F, KeyValueReader.Impl[F]],
    mutex: Mutex[F]
  ) extends DynamicKeyValueReader[F] {

    override def get(key: Key): F[Option[Value]] =
      hotswap.get.use(_.traverse(_.get(key)).map(_.flatten))

    override def keys: F[Set[Key]] =
      hotswap.get.use(_.traverse(_.keys).map(_.getOrElse(Set.empty)))

    override def updateFilters(f: KeyFilters => KeyFilters): F[Unit] =
      mutex.lock.surround {
        hotswap.get
          .use(kvReader => f(kvReader.map(_.keyFilters).getOrElse(Set.empty[String])).pure[F])
          .flatMap(newFilters => hotswap.swap(createReader(newFilters)))
          .void
      }

  }

  /** Create a DynamicKeyValueReader for a specific bucket with initial key filtering.
    *
    * <p>The returned Resource blocks during acquisition until the initial cache is warmed up (all matching keys are loaded) or the
    * warmupTimeout is reached. Once acquired, filters can be updated dynamically using updateFilters.
    *
    * @param js
    *   the JetStream context
    * @param bucketName
    *   the name of the key-value bucket
    * @param keyFilters
    *   initial set of wildcard filters to limit cached keys
    * @param warmupTimeout
    *   maximum time to wait for initial cache warmup
    * @return
    *   Resource yielding a DynamicKeyValueReader with populated cache
    */
  def make[F[_]: Async](
    js: JetStream[F],
    bucketName: String,
    keyFilters: KeyFilters,
    warmupTimeout: FiniteDuration
  ): Resource[F, DynamicKeyValueReader[F]] = for {

    kv <- js.keyValue(bucketName).toResource

    createReader = (filters: KeyFilters) => KeyValueReader.impl(kv, filters, warmupTimeout)

    (hotswap, _) <- Hotswap(createReader(keyFilters))

    mutex <- Mutex[F].toResource
  } yield new Impl(hotswap, createReader, mutex)

}
