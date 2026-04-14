package com.evolution.natseffect.impl

import cats.Applicative
import cats.effect.implicits.effectResourceOps
import cats.effect.std.Dispatcher as CEDispatcher
import cats.effect.{Async, MonadCancel, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.{Dispatcher, Message, Subscription}

private[natseffect] class WrappedDispatcher[F[_]: Async] private (
  wrapped: JDispatcher,
  catsDispatcher: CEDispatcher[F],
  subscriptions: Ref[F, Map[String, Set[WrappedSubscription[F]]]]
) extends WrappedConsumer(wrapped)
    with Dispatcher[F] {

  override def subscribe(subject: String, queue: Option[String])(messageHandler: Message => F[Unit]): F[Subscription[F]] =
    MonadCancel[F].uncancelable { _ =>
      for {
        messageHandler <- new CEMessageHandler[F](catsDispatcher, messageHandler).pure
        subscription <- queue match {
          case Some(value) =>
            Async[F].delay(new WrappedSubscription[F](wrapped.subscribe(subject, value, messageHandler), this))
          case None =>
            Async[F].delay(new WrappedSubscription[F](wrapped.subscribe(subject, messageHandler), this))
        }
        _ <- subscriptions.update(s => s + (subject -> (s.getOrElse(subject, Set.empty) + subscription)))
      } yield subscription
    }

  override def unsubscribe(subject: String): F[Unit] =
    MonadCancel[F].uncancelable { _ =>
      for {
        beforeUpdate <- subscriptions.getAndUpdate(_ - subject)
        toUnsubscribe = beforeUpdate.getOrElse(subject, Set.empty)
        _            <- Async[F].delay(toUnsubscribe.foreach(s => wrapped.unsubscribe(s.asJava)))
      } yield ()
    }

  private[natseffect] def deleteSubscription(subscription: WrappedSubscription[F]): F[Unit] =
    subscriptions.update {
      _.updatedWith(subscription.subject) {
        case None                => None
        case Some(subscriptions) => Some(subscriptions - subscription).filter(_.nonEmpty)
      }
    }

  private def close: F[Unit] = subscriptions.set(Map.empty)
}

private[natseffect] object WrappedDispatcher {
  def make[F[_]: Async](connection: JConnection): Resource[F, Dispatcher[F]] =
    for {
      catsDispatcher <- CEDispatcher.sequential
      natsDispatcher <- Resource.make(Async[F].delay(connection.createDispatcher()))(d => Async[F].delay(connection.closeDispatcher(d)))
      subscriptions  <- Ref.of[F, Map[String, Set[WrappedSubscription[F]]]](Map.empty).toResource
      result          = new WrappedDispatcher[F](natsDispatcher, catsDispatcher, subscriptions)
      _              <- Resource.make(Applicative[F].unit)(_ => result.close)
    } yield result
}
