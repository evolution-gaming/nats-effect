package io.nats.client

import cats.effect.Sync
import com.evolution.natseffect.jetstream.impl.JKeyValue

package object impl {

  /** We defer to the NatsJetStreamPullSubscription implementation for the per-pull reply subject, not to mirror its internal pull counter.
    */
  implicit class JNatsPullSubscriptionImplOps(a: JetStreamSubscription) {

    /** Like `pull(PullRequestOptions)` (same `raiseStatusWarnings`, no observer), but returns the reply subject the pull request was
      * published with - the subscription's wildcard inbox with `*` replaced by the internal pull counter. The server addresses everything
      * belonging to the pull, statuses included, to this subject.
      */
    def pullReturningSubject[F[_]](options: PullRequestOptions)(implicit F: Sync[F]): F[String] = F.delay {
      a match {
        case impl: NatsJetStreamPullSubscription => impl._pull(options, true, null)
        case _ => throw new IllegalArgumentException("Subscription is not a NatsJetStreamPullSubscription")
      }
    }
  }

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
