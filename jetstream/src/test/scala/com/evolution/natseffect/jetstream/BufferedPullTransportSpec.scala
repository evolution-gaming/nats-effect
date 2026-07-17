package com.evolution.natseffect.jetstream

import cats.effect.IO
import com.evolution.natseffect.impl.JMessage
import com.evolution.natseffect.jetstream.impl.BufferedPullTransport
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.Directive
import io.nats.client.impl.NatsMessage
import weaver.SimpleIOSuite

/** The transport's classification fusion: non-JetStream messages are dropped before inspection, and genuine JetStream data messages reach
  * the consumer-type inspection. This is the single site that upholds the `Directive.Deliver` promise of a verified JetStream message (the
  * status branch of the fusion is pinned by `PullStatusInterpreterSpec` and exercised end-to-end by the server-backed specs).
  */
object BufferedPullTransportSpec extends SimpleIOSuite {

  private val inspect: JMessage => IO[Directive.DataDirective] =
    message => IO.pure(Directive.Deliver(message))

  private def classify(message: JMessage): IO[Directive] =
    BufferedPullTransport.classify[IO](inspect)(message)

  private def jetStreamMessage(subject: String): JMessage =
    new NatsMessage(subject, null, Array.emptyByteArray) {
      override def isJetStream: Boolean = true
    }

  test("non-JetStream messages are skipped before inspection") {
    val plain = NatsMessage.builder().subject("test").data("plain".getBytes).build()
    classify(plain).map(directive => expect.same(directive, Directive.Skip))
  }

  test("JetStream data messages reach the inspection") {
    val message = jetStreamMessage("test.1")
    classify(message).map(directive => expect.same(directive, Directive.Deliver(message)))
  }
}
