package com.evolution.natseffect.jetstream

import cats.effect.IO
import com.evolution.natseffect.impl.JMessage
import com.evolution.natseffect.jetstream.impl.BufferedPullTransport
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.Directive
import io.nats.client.impl.NatsMessage
import io.nats.client.support.Status
import weaver.SimpleIOSuite

/** The transport's classification fusion: non-JetStream messages are dropped before inspection, genuine JetStream data messages reach the
  * consumer-type inspection, and a status is interpreted only when it answers the current pull - a stale status is skipped. (The
  * status-to-directive mapping itself is pinned by `PullStatusInterpreterSpec` and exercised end-to-end by the server-backed specs.)
  */
object BufferedPullTransportSpec extends SimpleIOSuite {

  private val CurrentPullSubject = "inbox.sub.2"

  private val inspect: JMessage => IO[Directive.DataDirective] =
    message => IO.pure(Directive.Deliver(message))

  private def classify(message: JMessage): IO[Directive] =
    BufferedPullTransport.classify[IO](inspect, IO.pure(CurrentPullSubject))(message)

  private def jetStreamMessage(subject: String): JMessage =
    new NatsMessage(subject, null, Array.emptyByteArray) {
      override def isJetStream: Boolean = true
    }

  private def statusMessage(subject: String): JMessage =
    new NatsMessage(subject, null, Array.emptyByteArray) {
      override def isStatusMessage: Boolean = true
      override def getStatus: Status        = new Status(Status.REQUEST_TIMEOUT_CODE, "Request Timeout")
    }

  test("non-JetStream messages are skipped before inspection") {
    val plain = NatsMessage.builder().subject("test").data("plain".getBytes).build()
    classify(plain).map(directive => expect.same(directive, Directive.Skip))
  }

  test("JetStream data messages reach the inspection") {
    val message = jetStreamMessage("test.1")
    classify(message).map(directive => expect.same(directive, Directive.Deliver(message)))
  }

  test("a status answering the current pull is interpreted") {
    classify(statusMessage(CurrentPullSubject)).map(directive => expect.same(directive, Directive.PullOver))
  }

  test("a status for a previous pull is stale and skipped") {
    classify(statusMessage("inbox.sub.1")).map(directive => expect.same(directive, Directive.Skip))
  }
}
