package com.evolution.natseffect.jetstream

import cats.effect.{IO, Resource}
import com.evolution.natseffect.Nats
import io.nats.client.api.{DiscardPolicy, RetentionPolicy, StorageType, StreamConfiguration}
import weaver.GlobalRead

import scala.concurrent.duration.DurationInt

class JetStreamManagementSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("stream lifecycle and config management") { ctx =>
    for {
      stream1    <- randomStreamName.toResource
      stream2    <- randomStreamName.toResource
      connection <- Nats.connect(ctx.url())
      js         <- JetStream.fromConnection[IO](connection).toResource
      jsm        <- js.jetStreamManagement().toResource

      // Create stream with comprehensive initial configuration
      _ <- Resource.make {
        jsm.addStream(
          StreamConfiguration
            .builder()
            .name(stream1)
            .subjects(s"$stream1.*")
            .storageType(StorageType.Memory)
            .maxMessages(100)
            .maxBytes(1024 * 1024)
            .maxAge(1.hour.toMillis)
            .replicas(1)
            .retentionPolicy(RetentionPolicy.Limits)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(2.minutes.toMillis)
            .build()
        )
      }(_ => jsm.deleteStream(stream1).attempt.void)

      // Update stream configuration with different values
      updatedInfo <- jsm
        .updateStream(
          StreamConfiguration
            .builder()
            .name(stream1)
            .subjects(s"$stream1.*", s"$stream1.extra.*") // Add more subjects
            .storageType(StorageType.Memory)              // Must keep same storage type
            .maxMessages(500)                             // Increased
            .maxBytes(5 * 1024 * 1024)                    // Increased
            .maxAge(2.hours.toMillis)                     // Increased
            .replicas(1)
            .retentionPolicy(RetentionPolicy.Interest) // Changed (but not to/from WorkQueue)
            .discardPolicy(DiscardPolicy.New)          // Changed
            .duplicateWindow(5.minutes.toMillis)       // Increased
            .build()
        )
        .toResource

      // List streams
      streams <- jsm.getStreamNames().toResource

      // Test stream deletion
      _ <- jsm
        .addStream(
          StreamConfiguration.builder().name(stream2).subjects(s"$stream2.*").build()
        )
        .toResource

      deleted <- jsm.deleteStream(stream2).toResource
      failed  <- js.streamContext(stream2).attempt.toResource

    } yield
    // Updated configuration assertions
    expect.eql(updatedInfo.config.getName, stream1) &&
      expect.eql(updatedInfo.config.getMaxMsgs, 500L) &&
      expect.eql(updatedInfo.config.getMaxBytes, 5L * 1024L * 1024L) &&
      expect.eql(updatedInfo.config.getMaxAge.toMillis, 2.hours.toMillis) &&
      expect(updatedInfo.config.getRetentionPolicy == RetentionPolicy.Interest) &&
      expect(updatedInfo.config.getDiscardPolicy == DiscardPolicy.New) &&
      expect.eql(updatedInfo.config.getDuplicateWindow.toMillis, 5.minutes.toMillis) &&
      expect.eql(updatedInfo.config.getSubjects.size, 2) &&
      // List streams assertions
      expect(streams.contains(stream1)) &&
      // Delete assertions
      expect(deleted) &&
      expect(failed.isLeft)
  }
}
