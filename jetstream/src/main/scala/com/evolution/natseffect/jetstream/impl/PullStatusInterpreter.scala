package com.evolution.natseffect.jetstream.impl

import com.evolution.natseffect.jetstream.impl.PacedPullEngine.Directive
import io.nats.client.support.Status

/** Interpretation of JetStream pull status messages, expressed in the engine's [[Directive.StatusDirective]] subset - the type guarantees a
  * status can never map to Deliver or Restart.
  *
  * <p>A synchronous read path gets this mapping from jnats `PullMessageManager.manageStatus` inside `nextMessage`; the buffered
  * subscription receives raw status messages, so the mapping is mirrored here. Heartbeats normally never reach the queue - the pull
  * manager's beforeQueueProcessor filters them on the reader thread. Re-verify against `PullMessageManager.manageStatus` on jnats upgrades.
  */
private[natseffect] object PullStatusInterpreter {

  final class PullStatusException(val status: Status)
      extends Exception(s"Pull terminated with status ${status.getCode} ${status.getMessage}")

  def interpret(status: Status): Directive.StatusDirective =
    status.getCode match {
      case Status.NOT_FOUND_CODE | Status.REQUEST_TIMEOUT_CODE | Status.NO_RESPONDERS_CODE =>
        Directive.PullOver
      case Status.CONFLICT_CODE =>
        val message = Option(status.getMessage).getOrElse("")
        if (message.startsWith(Status.EXCEEDED_MAX_PREFIX) || message == Status.SERVER_SHUTDOWN)
          Directive.Skip
        else if (
          message == Status.BATCH_COMPLETED ||
          message == Status.LEADERSHIP_CHANGE ||
          message == Status.MESSAGE_SIZE_EXCEEDS_MAX_BYTES
        )
          Directive.PullOver
        else
          // Unknown 409s and e.g. "Consumer Deleted" / "Consumer is push based" are errors
          Directive.Fail(new PullStatusException(status))
      case Status.PIN_ERROR_CODE =>
        Directive.PullOver
      case Status.FLOW_OR_HEARTBEAT_STATUS_CODE =>
        // Defensive: heartbeats are filtered on the reader thread before reaching the queue
        Directive.Skip
      case _ =>
        Directive.Fail(new PullStatusException(status))
    }
}
