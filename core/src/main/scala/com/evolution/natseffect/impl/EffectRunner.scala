package com.evolution.natseffect.impl

import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import com.evolutiongaming.catshelper.ToTry

private[natseffect] trait EffectRunner[F[_]] {

  def run[T](effect: F[T]): T

}
private[natseffect] object EffectRunner {
  def fromToTry[F[_]](toTry: ToTry[F]): EffectRunner[F] = new EffectRunner[F] {
    override def run[T](effect: F[T]): T = toTry(effect).get
  }

  def dispatcherBased[F[_]: Async]: Resource[F, EffectRunner[F]] =
    Dispatcher.parallel.map { dispatcher =>
      new EffectRunner[F] {
        override def run[T](effect: F[T]): T = dispatcher.unsafeRunSync(effect)
      }
    }
}
