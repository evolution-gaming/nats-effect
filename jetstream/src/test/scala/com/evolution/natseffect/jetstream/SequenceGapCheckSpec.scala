package com.evolution.natseffect.jetstream

import cats.effect.{IO, Ref}
import com.evolution.natseffect.impl.JMessage
import com.evolution.natseffect.jetstream.impl.PacedConsumerContexts
import com.evolution.natseffect.jetstream.impl.PacedConsumerContexts.OrderedContextState
import com.evolution.natseffect.jetstream.impl.PacedPullEngine.Directive
import io.nats.client.impl.{NatsJetStreamMetaData, NatsMessage}
import weaver.SimpleIOSuite

/** The ordered gap check against real jnats metadata: messages carry a genuine `\$JS.ACK` reply subject parsed by `NatsJetStreamMetaData`,
  * so the metadata-extraction chain is covered, not just the sequence arithmetic (the engine specs script directives and never parse
  * metadata).
  */
object SequenceGapCheckSpec extends SimpleIOSuite {

  /** A JetStream data message as the transport would see it: metadata parsed from the ack reply subject
    * (`\$JS.ACK.<stream>.<consumer>.<delivered>.<streamSeq>.<consumerSeq>.<timestampNanos>.<pending>`).
    */
  private def jetStreamMessage(streamSeq: Long, consumerSeq: Long): JMessage =
    new NatsMessage("orders.1", s"$$JS.ACK.stream.consumer.1.$streamSeq.$consumerSeq.1700000000000000000.0", Array.emptyByteArray) {
      override def isJetStream: Boolean              = true
      override def metaData(): NatsJetStreamMetaData = new NatsJetStreamMetaData(this)
    }

  test("an in-sequence message is delivered and advances the position") {
    for {
      state   <- Ref.of[IO, OrderedContextState](OrderedContextState.Initial)
      message  = jetStreamMessage(streamSeq = 10L, consumerSeq = 1L)
      verdict <- PacedConsumerContexts.sequenceGapCheck(state)(message)
      after   <- state.get
    } yield expect.same(verdict, Directive.Deliver(message)) &&
      expect.eql(after.position.expectedConsumerSeq, 2L) &&
      expect.eql(after.position.lastStreamSeq, 10L)
  }

  test("a consumer-sequence gap restarts without advancing the position") {
    for {
      state <- Ref.of[IO, OrderedContextState](OrderedContextState.Initial)
      _     <- PacedConsumerContexts.sequenceGapCheck(state)(jetStreamMessage(streamSeq = 10L, consumerSeq = 1L))

      // Consumer sequence 3 arrives where 2 was expected - a message was lost in transit
      verdict <- PacedConsumerContexts.sequenceGapCheck(state)(jetStreamMessage(streamSeq = 12L, consumerSeq = 3L))
      after   <- state.get
    } yield expect(verdict match {
      case Directive.Restart(reason) => reason.contains("expected 2") && reason.contains("received 3")
      case _                         => false
    }) &&
      expect.eql(after.position.expectedConsumerSeq, 2L) &&
      expect.eql(after.position.lastStreamSeq, 10L)
  }
}
