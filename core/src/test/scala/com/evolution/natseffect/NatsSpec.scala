package com.evolution.natseffect

import berlin.yuna.natsserver.logic.Nats
import cats.effect.{IO, Resource}
import weaver.{Expectations, GlobalRead, IOSuite, TestName}

import scala.util.Random

abstract class NatsSpec(global: GlobalRead) extends IOSuite {

  override def maxParallelism = 1

  override type Res = Nats

  override def sharedResource: Resource[IO, Nats] = SharedResources.get(global)

  protected def testResource(name: TestName)(run: Res => Resource[IO, Expectations]): Unit =
    test(name) { (res: Res) =>
      for {
        t           <- run(res).allocated
        (res, close) = t
        _           <- close
      } yield res
    }

  protected def randomSubject: IO[String] = IO(s"Subject${Random.nextInt()}")
}
