package io.nats.client

import cats.effect.Sync
import com.evolution.natseffect.jetstream.impl.JKeyValue

package object impl {

  /** We defer to NatsKeyValue implementation for stream and subject values calculation, not to reimplement the internal logic
    */
  implicit class JNatsKeyValueImplOps(a: JKeyValue) {
    private def visitImpl[F[_], R](visitor: NatsKeyValue => R)(implicit F: Sync[F]): F[R] = F.delay {
      a match {
        case impl: NatsKeyValue => visitor(impl)
        case _                  => throw new IllegalArgumentException(s"KeyValue is not a NatsKeyValue")
      }
    }

    def getStreamName[F[_]](implicit F: Sync[F]): F[String] =
      visitImpl(_.getStreamName)

    def readSubject[F[_]](key: String)(implicit F: Sync[F]): F[String] =
      visitImpl(_.readSubject(key))
  }

}
