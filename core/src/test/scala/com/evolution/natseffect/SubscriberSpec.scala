package com.evolution.natseffect

import cats.effect.std.CountDownLatch
import cats.effect.{Deferred, IO, Ref}
import cats.implicits.toTraverseOps
import weaver.GlobalRead

import scala.concurrent.duration.DurationInt
import scala.util.Random

class SubscriberSpec(global: GlobalRead) extends NatsSpec(global) {

  testResource("createInbox generates random values") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      checks     <- Ref.of(Set.empty[String]).toResource
      _          <- connection.createInbox.flatMap(i => checks.update(_ + i)).replicateA_(10000).toResource

      finalSize <- checks.get.map(_.size).toResource
    } yield expect(finalSize == 10000)
  }

  testResource("deliver a single message") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject    <- randomSubject.toResource
      deferred   <- Deferred[IO, Message].toResource
      _ <- dispatcher
        .subscribe(subject) { msg =>
          deferred.complete(msg).void
        }
        .toResource

      _ <- connection.publish(subject, Array.fill(16)(0)).toResource

      msg <- deferred.get.toResource
    } yield expect(msg.subject == subject) && expect(msg.replyTo.isEmpty) && expect(msg.data.get.length == 16)
  }

  testResource("deliver multiple messages") { ctx =>
    for {
      connection       <- Nats.connect(ctx.url())
      dispatcher       <- connection.createDispatcher()
      receivedMessages <- Ref.of(Seq.empty[Message]).toResource
      latch            <- CountDownLatch(3).toResource
      subject          <- randomSubject.toResource

      _ <- dispatcher
        .subscribe(subject) { msg =>
          receivedMessages.update(_ :+ msg) *> latch.release
        }
        .toResource

      _ <- connection.publish(subject, Array.fill(16)(0)).replicateA_(3).toResource
      _ <- latch.await.timeout(5.second).toResource

      deliveredMessages <- receivedMessages.get.toResource
    } yield expect(deliveredMessages.size == 3) && forEach(deliveredMessages) { msg =>
      expect(msg.subject == subject) && expect(msg.replyTo.isEmpty) && expect(msg.data.get.length == 16)
    }
  }

  testResource("deliver according to the queue") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject    <- randomSubject.toResource
      received1  <- Ref.of(0).toResource
      received2  <- Ref.of(0).toResource
      latch      <- CountDownLatch(100).toResource

      _ <- dispatcher
        .subscribe(subject, Some("queue")) { _ =>
          received1.update(_ + 1) *> latch.release
        }
        .toResource

      _ <- dispatcher
        .subscribe(subject, Some("queue")) { _ =>
          received2.update(_ + 1) *> latch.release
        }
        .toResource

      _ <- connection.publish(subject, Array.empty).replicateA_(100).toResource
      _ <- latch.await.timeout(5.second).toResource

      _ <- connection.flushBuffer.toResource

      _ <- IO.sleep(1.second).toResource // to make sure that one subscription won't take extra messages

      received1 <- received1.get.toResource
      received2 <- received2.get.toResource

    } yield expect(received1 + received2 == 100 && received1 > 0 && received2 > 0)
  }

  testResource("not receive messages when unsubscribed") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject    <- randomSubject.toResource
      received1  <- Ref.of(0).toResource
      received2  <- Ref.of(0).toResource
      latch1     <- CountDownLatch(50).toResource
      latch2     <- CountDownLatch(100).toResource

      sub1 <- dispatcher
        .subscribe(subject) { _ =>
          received1.update(_ + 1) *> latch1.release
        }
        .toResource

      _ <- dispatcher
        .subscribe(subject) { _ =>
          received2.update(_ + 1) *> latch2.release
        }
        .toResource

      _ <- connection.publish(subject, Array.empty).replicateA_(50).toResource
      _ <- latch1.await.timeout(5.second).toResource
      _ <- sub1.unsubscribe.toResource

      _ <- connection.publish(subject, Array.empty).replicateA_(50).toResource
      _ <- latch2.await.timeout(5.second).toResource

      received1 <- received1.get.toResource
      received2 <- received2.get.toResource
    } yield expect(received1 == 50 && received2 == 100)
  }

  testResource("not receive messages when automatically unsubscribed (after received N messages)") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject    <- randomSubject.toResource
      received1  <- Ref.of(0).toResource
      received2  <- Ref.of(0).toResource
      latch      <- CountDownLatch(100).toResource

      sub1 <- dispatcher
        .subscribe(subject) { _ =>
          received1.update(_ + 1)
        }
        .toResource
      _ <- sub1.unsubscribe(50).toResource

      _ <- dispatcher
        .subscribe(subject) { _ =>
          received2.update(_ + 1) *> latch.release
        }
        .toResource

      _ <- connection.publish(subject, Array.empty).replicateA_(100).toResource
      _ <- latch.await.timeout(5.second).toResource

      received1 <- received1.get.toResource
      received2 <- received2.get.toResource
    } yield expect(received1 == 50 && received2 == 100)
  }

  testResource("not receive messages when unsubscribed via dispatcher") { ctx =>
    for {
      connection <- Nats.connect(ctx.url())
      dispatcher <- connection.createDispatcher()
      subject1   <- randomSubject.toResource
      subject2   <- randomSubject.toResource
      received1  <- Ref.of(0).toResource
      received2  <- Ref.of(0).toResource
      received3  <- Ref.of(0).toResource
      latch1     <- CountDownLatch(50).toResource
      latch2     <- CountDownLatch(100).toResource

      _ <- dispatcher
        .subscribe(subject1)(_ => received1.update(_ + 1) *> latch1.release)
        .toResource

      _ <- dispatcher
        .subscribe(subject1)(_ => received2.update(_ + 1))
        .toResource

      _ <- dispatcher
        .subscribe(subject2)(_ => received3.update(_ + 1) *> latch2.release)
        .toResource

      _ <- connection.publish(subject1, Array.empty).replicateA_(50).toResource
      _ <- connection.publish(subject2, Array.empty).replicateA_(50).toResource

      _ <- latch1.await.timeout(5.second).toResource
      _ <- dispatcher.unsubscribe(subject1).toResource

      _ <- connection.publish(subject1, Array.empty).replicateA_(50).toResource
      _ <- connection.publish(subject2, Array.empty).replicateA_(50).toResource

      _ <- latch2.await.timeout(5.second).toResource

      received1 <- received1.get.toResource
      received2 <- received2.get.toResource
      received3 <- received3.get.toResource

    } yield expect(received1 == 50 && received2 == 50 && received3 == 100)
  }

  testResource("destroy all subscriptions when dispatcher is released") { ctx =>
    for {
      connection                     <- Nats.connect(ctx.url())
      d                              <- connection.createDispatcher().allocated.toResource
      (dispatcher, releaseDispatcher) = d
      subject1                       <- randomSubject.toResource
      subject2                       <- randomSubject.toResource
      received1                      <- Ref.of(0).toResource
      received2                      <- Ref.of(0).toResource
      received3                      <- Ref.of(0).toResource
      latch                          <- CountDownLatch(150).toResource

      _ <- dispatcher
        .subscribe(subject1)(_ => received1.update(_ + 1) *> latch.release)
        .toResource

      _ <- dispatcher
        .subscribe(subject1)(_ => received2.update(_ + 1) *> latch.release)
        .toResource

      _ <- dispatcher
        .subscribe(subject2)(_ => received3.update(_ + 1) *> latch.release)
        .toResource

      _ <- connection.publish(subject1, Array.empty).replicateA_(50).toResource
      _ <- connection.publish(subject2, Array.empty).replicateA_(50).toResource

      _ <- latch.await.timeout(5.second).toResource
      _ <- releaseDispatcher.toResource

      _ <- connection.publish(subject1, Array.empty).replicateA_(50).toResource
      _ <- connection.publish(subject2, Array.empty).replicateA_(50).toResource

      _ <- connection.flushBuffer.toResource

      _ <- IO.sleep(1.second).toResource

      received1 <- received1.get.toResource
      received2 <- received2.get.toResource
      received3 <- received3.get.toResource

    } yield expect(received1 == 50 && received2 == 50 && received3 == 50)
  }

  testResource("match subscription parameters with the dispatcher") { ctx =>
    for {
      connection    <- Nats.connect(ctx.url())
      dispatcher    <- connection.createDispatcher()
      subject1      <- randomSubject.toResource
      subject2      <- randomSubject.toResource
      subscription1 <- dispatcher.subscribe(subject1)(_ => IO.unit).toResource
      subscription2 <- dispatcher.subscribe(subject2, Some("test-queue"))(_ => IO.unit).toResource
    } yield expect(
      subscription1.subject == subject1 &&
        subscription2.subject == subject2 &&
        subscription1.queueName.isEmpty &&
        subscription2.queueName.contains("test-queue") &&
        subscription1.dispatcher == dispatcher &&
        subscription2.dispatcher == dispatcher
    )
  }

  testResource("request-response semantic") { ctx =>
    for {
      connection       <- Nats.connect(ctx.url())
      dispatcher       <- connection.createDispatcher()
      subject          <- randomSubject.toResource
      expectedResponse <- IO(s"ACK_{${Random.nextInt()}}").toResource
      _ <- dispatcher
        .subscribe(subject) { msg =>
          val request = new String(msg.data.get)
          msg.replyTo.traverse(replyTo => connection.publish(replyTo, (request + expectedResponse).getBytes())).void
        }
        .toResource

      request  <- IO(s"request_{${Random.nextInt()}}").toResource
      response <- connection.request(subject, request.getBytes).toResource
    } yield expect(response.data.map(new String(_)).contains(request + expectedResponse))
  }

}
