package com.evolution.natseffect.loadtest

import com.evolution.natseffect.jetstream.Warmup

import scala.concurrent.duration.FiniteDuration

object Report {

  def render(r: RunResult): String = {
    val cfg = r.config

    val successes = r.outcomes.collect { case WatcherOutcome(_, Right(s: Warmup.Result.Success), _) => s.time }
    val timeouts = r.outcomes.count {
      case WatcherOutcome(_, Right(_: Warmup.Result.Timeout), _) => true
      case _                                                     => false
    }
    val canceled = r.outcomes.count {
      case WatcherOutcome(_, Right(_: Warmup.Result.Canceled), _) => true
      case _                                                      => false
    }
    val failures     = r.outcomes.collect { case WatcherOutcome(_, Left(e), _) => e }
    val failureCount = failures.size

    val warmupSorted = successes.map(_.toMillis).sorted.toVector
    val lagSorted    = r.livenessLagsMillis.sorted

    val recreations    = r.outcomes.map(_.recreations)
    val totalRecreated = recreations.sum
    val maxRecreated   = if (recreations.isEmpty) 0 else recreations.max

    val wallSeconds = r.consumeWallTime.toMillis / 1000.0
    val msgPerSec   = if (wallSeconds > 0) r.messagesHandled / wallSeconds else 0.0
    val mibPerSec   = msgPerSec * cfg.valueSizeBytes / 1024 / 1024

    val lagP50 = Probes.percentile(lagSorted, 50)
    val lagP99 = Probes.percentile(lagSorted, 99)
    val lagMax = if (lagSorted.isEmpty) 0L else lagSorted.last

    val livenessDead = lagMax >= 5000

    val pathology = List(
      Option.when(r.droppedMessages > 0)(s"drops=${r.droppedMessages}"),
      Option.when(r.slowConsumerEvents > 0)(s"slowConsumers=${r.slowConsumerEvents}"),
      Option.when(totalRecreated > 0)(s"recreations>=$totalRecreated"),
      Option.when(timeouts > 0)(s"warmupTimeouts=$timeouts"),
      Option.when(failureCount > 0)(s"watcherFailures=$failureCount"),
      Option.when(r.outgoingDiscards > 0)(s"outgoingDiscards=${r.outgoingDiscards}"),
      Option.when(livenessDead)(s"livenessMaxLag=${lagMax}ms")
    ).flatten

    val verdict =
      if (pathology.nonEmpty) s"PROBLEM REPRODUCED (${pathology.mkString(", ")})"
      else "CLEAN (no drops, no slow consumers, no recreations, all warmups succeeded, liveness responsive)"

    def line(label: String, value: String) = f"  $label%-22s $value"

    val sb = new StringBuilder
    sb.append("\n=== nats-effect KV consumption load test ===\n")
    sb.append(line("scenario", cfg.scenario.name) + "\n")
    sb.append(line("watchers", cfg.watchers.toString) + "\n")
    sb.append(
      line("bucket", f"${cfg.keys} keys x ${cfg.valueSizeBytes / 1024.0}%.1f KiB = ${cfg.totalBytes / 1024.0 / 1024.0}%.0f MiB") + "\n"
    )
    sb.append(line("compute threads", cfg.computeThreads.toString) + "\n")
    sb.append(line("handler cost", s"~${cfg.handlerSpinMicros} us/msg (spin)") + "\n")
    sb.append(line("warmup timeout", s"${cfg.warmupTimeout.toSeconds} s") + "\n")
    sb.append("--- timings ---\n")
    sb.append(line("populate time", fmt(r.populateTime)) + "\n")
    sb.append(line("consume wall time", fmt(r.consumeWallTime)) + "\n")
    sb.append(
      line(
        "warmups",
        s"success=${successes.size} timeout=$timeouts canceled=$canceled failed=$failureCount" + (
          if (warmupSorted.nonEmpty)
            f" | success times: min=${warmupSorted.head / 1000.0}%.1fs median=${Probes.percentile(warmupSorted, 50) / 1000.0}%.1fs max=${warmupSorted.last / 1000.0}%.1fs"
          else ""
        )
      ) + "\n"
    )
    failures.headOption.foreach(e => sb.append(line("first watcher failure", e.toString.take(160)) + "\n"))
    sb.append("--- consumption ---\n")
    sb.append(line("messages handled", f"${r.messagesHandled} (${msgPerSec}%.0f msg/s, $mibPerSec%.1f MiB/s)") + "\n")
    sb.append("--- pathology ---\n")
    sb.append(line("dropped messages", s"${r.droppedMessages} (consume phase only)") + "\n")
    sb.append(line("slow consumer events", r.slowConsumerEvents.toString) + "\n")
    sb.append(line("consumer recreations", s">=$totalRecreated total, max $maxRecreated per watcher (sampled lower bound)") + "\n")
    sb.append(line("errors / exceptions", s"${r.errorsOccurred} / ${r.exceptionsOccurred}") + "\n")
    sb.append(line("outgoing discards", r.outgoingDiscards.toString) + "\n")
    sb.append(
      line("liveness lag", s"p50=${lagP50}ms p99=${lagP99}ms max=${lagMax}ms" + (if (livenessDead) "  << DEAD (>5s)" else "")) + "\n"
    )
    sb.append(line("peak heap", s"${r.peakHeapBytes / 1024 / 1024} MiB") + "\n")
    sb.append("--- verdict ---\n")
    sb.append(s"  $verdict\n")

    sb.append(
      "RESULT_JSON " + jsonLine(
        "scenario"         -> quote(cfg.scenario.name),
        "watchers"         -> cfg.watchers.toString,
        "keys"             -> cfg.keys.toString,
        "valueSizeBytes"   -> cfg.valueSizeBytes.toString,
        "computeThreads"   -> cfg.computeThreads.toString,
        "spinMicros"       -> cfg.handlerSpinMicros.toString,
        "populateMillis"   -> r.populateTime.toMillis.toString,
        "consumeMillis"    -> r.consumeWallTime.toMillis.toString,
        "warmupSuccess"    -> successes.size.toString,
        "warmupTimeout"    -> timeouts.toString,
        "watcherFailures"  -> failureCount.toString,
        "messagesHandled"  -> r.messagesHandled.toString,
        "msgPerSec"        -> math.round(msgPerSec).toString,
        "dropped"          -> r.droppedMessages.toString,
        "slowConsumers"    -> r.slowConsumerEvents.toString,
        "recreations"      -> totalRecreated.toString,
        "errors"           -> r.errorsOccurred.toString,
        "exceptions"       -> r.exceptionsOccurred.toString,
        "outgoingDiscards" -> r.outgoingDiscards.toString,
        "livenessLagP99Ms" -> lagP99.toString,
        "livenessLagMaxMs" -> lagMax.toString,
        "peakHeapMiB"      -> (r.peakHeapBytes / 1024 / 1024).toString,
        "reproduced"       -> pathology.nonEmpty.toString
      ) + "\n"
    )

    sb.result()
  }

  private def fmt(d: FiniteDuration): String = f"${d.toMillis / 1000.0}%.1f s"

  private def quote(s: String): String = "\"" + s + "\""

  private def jsonLine(fields: (String, String)*): String =
    fields.map { case (k, v) => s"${quote(k)}: $v" }.mkString("{", ", ", "}")
}
