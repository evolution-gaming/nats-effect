package com.evolution.natseffect.jetstream

import weaver.GlobalRead

class JetStreamPublisherSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("publish messages with various configurations") { ctx =>
    for {
      (js, streamName, subject) <- setupStream(ctx)

      publisher <- js.jetStreamPublisher().toResource
      sc        <- js.streamContext(streamName).toResource

      // Basic publish with acknowledgment
      ack1        <- publisher.publish(subject, "message 1".getBytes()).toResource
      streamInfo1 <- sc.getStreamInfo().toResource

      // Publish with headers
      headers      = new io.nats.client.impl.Headers()
      _            = headers.add("X-Custom-Header", "test-value")
      ack2        <- publisher.publish(subject, headers, "message 2 with headers".getBytes()).toResource
      streamInfo2 <- sc.getStreamInfo().toResource

      // Publish one more message
      ack3        <- publisher.publish(subject, "message 3".getBytes()).toResource
      streamInfo3 <- sc.getStreamInfo().toResource

    } yield expect.eql(streamInfo1.config.getName, streamName) &&
      expect.eql(streamInfo1.streamState.getMsgCount, 1L) &&
      expect.eql(ack1.getSeqno, 1L) &&
      expect.eql(streamInfo2.streamState.getMsgCount, 2L) &&
      expect.eql(ack2.getSeqno, 2L) &&
      expect.eql(streamInfo3.streamState.getMsgCount, 3L) &&
      expect.eql(ack3.getSeqno, 3L)
  }
}
