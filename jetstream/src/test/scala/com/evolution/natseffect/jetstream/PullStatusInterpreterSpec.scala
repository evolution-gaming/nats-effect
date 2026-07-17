package com.evolution.natseffect.jetstream

import com.evolution.natseffect.jetstream.impl.PacedPullEngine.Directive
import com.evolution.natseffect.jetstream.impl.PullStatusInterpreter
import io.nats.client.support.Status
import weaver.FunSuite

/** The interpreter mirrors jnats `PullMessageManager.manageStatus`; these cases pin the mapping so a jnats upgrade that shifts status
  * semantics fails loudly. That a status can never map to Deliver or Restart is enforced by the return type (`Directive.StatusDirective`),
  * so it needs no test.
  */
object PullStatusInterpreterSpec extends FunSuite {

  private def interpret(code: Int, message: String): Directive.StatusDirective =
    PullStatusInterpreter.interpret(new Status(code, message))

  test("terminus statuses end the pull") {
    expect.same(interpret(404, "No Messages"), Directive.PullOver) &&
    expect.same(interpret(408, "Request Timeout"), Directive.PullOver) &&
    expect.same(interpret(503, "No Responders Available For Request"), Directive.PullOver) &&
    expect.same(interpret(409, Status.BATCH_COMPLETED), Directive.PullOver) &&
    expect.same(interpret(409, Status.LEADERSHIP_CHANGE), Directive.PullOver) &&
    expect.same(interpret(409, Status.MESSAGE_SIZE_EXCEEDS_MAX_BYTES), Directive.PullOver) &&
    expect.same(interpret(423, "Pin Id Mismatch"), Directive.PullOver)
  }

  test("informational statuses are skipped") {
    expect.same(interpret(409, "Exceeded MaxRequestBatch of 10"), Directive.Skip) &&
    expect.same(interpret(409, Status.SERVER_SHUTDOWN), Directive.Skip) &&
    expect.same(interpret(100, "Idle Heartbeat"), Directive.Skip)
  }

  test("unknown and consumer-fatal statuses fail") {
    def isFail(directive: Directive.StatusDirective): Boolean = directive match {
      case Directive.Fail(_) => true
      case _                 => false
    }
    expect(isFail(interpret(409, "Consumer Deleted"))) &&
    expect(isFail(interpret(409, "Consumer is push based"))) &&
    expect(isFail(interpret(500, "boom")))
  }
}
