# Load test results: KV warm-up collapse reproduction

*Date: 2026-07-14 (v2 — review-hardened harness). Produced by the `loadtest` module; see the
README "Load testing" section for how to run it.*

*v2 harness changes that affect the numbers below: the watcher handler now mirrors the production
`KeyValueReader` per-entry cache update (heavier per-message compute than v1's bare counter);
"dropped messages" is a consume-phase delta (populate excluded); "consumer recreations" is reported
as a sampled lower bound; error-listener counters are recorded synchronously on jnats callback
threads (no starvation bias); output formatting is locale-pinned.*

## Setup

| | |
|---|---|
| Workload | 20 KV watchers (`watchAll`, `LatestValues`) started simultaneously against a bucket of 18,000 keys × 12 KiB = 211 MiB |
| Handler | per-entry cache `Ref` update with revision comparison (mirrors `KeyValueReader`) + ~5 µs busy-spin (matches the measured 3–6 µs production handler) |
| Compute pool | **2 threads** (mimics pod CPU limits; the incident precondition) |
| Warm-up timeout | 60 s |
| Environment | Apple M3 Max (16 cores, pool restricted to 2), 48 GiB RAM, macOS 26.5.1, OpenJDK 21.0.10, `-Xmx4g`, embedded nats-server 2.12.1 (loopback), jnats 2.25.1 |
| Command | `sbt 'loadtest/run scenario=<baseline\|unlimited> watchers=20 keys=18000 valueSize=12288 warmupTimeoutSec=60 computeThreads=2'` |

Scenarios (as measured, against pre-#10 `master`):

- **baseline** — jnats default pending limits per dispatcher (512 Ki msgs / 64 MiB).
- **unlimited** — the interim fix: unlimited pending limits on JS dispatchers (what PR #10 has
  since baked into `master`).

> Note: after #10 merged, every JetStream dispatcher is hardcoded to unlimited pending limits, so
> on current `master` both scenarios run the same configuration (the **unlimited** column) and the
> drop-reproducing **baseline** configuration requires a pre-#10 checkout. The numbers below are
> preserved as recorded.

## Results

| Metric | baseline | unlimited (0,0) |
|---|---|---|
| Warm-ups (of 20) | 11 success / **4 timeout / 5 failed** | 9 success / **7 timeout / 4 failed** |
| Success times (min / median / max) | 45.5 / 49.4 / 57.5 s | 45.3 / 47.3 / 51.4 s |
| Dropped messages (consume phase) | **3,581** | 0 |
| Slow-consumer events | **243** | 0 |
| Ordered-consumer recreations (sampled lower bound) | ≥17 (max 2/watcher) | ≥9 (max 1/watcher) |
| Watcher failures (JS control-plane timeout) | **5** | **4** |
| Throughput | 3,930 msg/s (46.1 MiB/s) | 2,799 msg/s (32.8 MiB/s) |
| Liveness probe max lag | **10,062 ms — DEAD** | 4,143 ms |
| Liveness p50 / p99 | 3 / 17 ms | 3 / 6 ms |
| Peak heap (for 211 MiB of data) | 1,445 MiB | **1,705 MiB** |
| Outgoing discards | 0 | 0 |
| Verdict | PROBLEM REPRODUCED | PROBLEM REPRODUCED |

Raw `RESULT_JSON` lines:

```json
{"scenario": "baseline", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 690, "consumeMillis": 60025, "warmupSuccess": 11, "warmupTimeout": 4, "watcherFailures": 5, "messagesHandled": 235881, "msgPerSec": 3930, "dropped": 3581, "slowConsumers": 243, "recreations": 17, "errors": 0, "exceptions": 1, "outgoingDiscards": 0, "livenessLagP99Ms": 17, "livenessLagMaxMs": 10062, "peakHeapMiB": 1445, "reproduced": true}
{"scenario": "unlimited", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 738, "consumeMillis": 60030, "warmupSuccess": 9, "warmupTimeout": 7, "watcherFailures": 4, "messagesHandled": 168026, "msgPerSec": 2799, "dropped": 0, "slowConsumers": 0, "recreations": 9, "errors": 0, "exceptions": 1, "outgoingDiscards": 0, "livenessLagP99Ms": 6, "livenessLagMaxMs": 4143, "peakHeapMiB": 1705, "reproduced": true}
```

## Interpretation

**Baseline reproduces the full incident signature.** The warm-up burst overruns the per-consumer
dispatcher pending limits: 3,581 messages dropped client-side during the consume phase, 243
slow-consumer error events (the error-metric bump seen in production), ≥17 ordered-consumer
recreations from the resulting sequence gaps, 9 of 20 watchers never completing warm-up (4 timed
out, 5 crashed on JetStream control-plane timeouts), and the simulated liveness probe stalling for
10 s (a k8s probe with a couple-of-seconds timeout would have failed → restart loop).

**The `(0,0)` interim fix removes the drop pathway but not the systemic overload.** As predicted:
zero drops and zero slow-consumer events. But with the production-fidelity handler the overall
outcome is *no better than baseline*:

- **11 of 20 watchers never completed warm-up** (7 timeouts, 4 control-plane crashes with
  `IOException: Timeout or no response waiting for NATS JetStream server`) — the JetStream
  request/reply plane starves while the unthrottled burst floods the connection.
- **≥9 consumer recreations still happened** — without drops, these come from missed idle
  heartbeats under starvation, each one a synchronous `CONSUMER.CREATE` executed on a compute
  thread.
- **Peak heap is the highest of both scenarios** (1.7 GiB for 211 MiB of payload) — nothing bounds
  the client-side buffer; a genuinely slow consumer would buffer the entire bucket per watcher.
- Throughput was the *lowest* of both scenarios (2,799 msg/s), and the liveness probe still stalled
  for 4.1 s.

Both scenarios confirm the same conclusion: pending-limit tuning changes *which* pathology you get
(drops+recreation loop vs. memory+control-plane starvation), not *whether* the system degrades.
The arrival rate has to be coupled to processing — genuine back-pressure (a fetch-based consume
path) is the structural fix.

## Reproducing / next steps

- Re-run: commands above; exit codes: 0 = finished (see verdict), 1 = bad args, 2 = watchdog killed
  a deadlocked run (thread dump printed), 3 = crash. All numeric args are validated; a watchdog
  halt can orphan the embedded nats-server (the next run detects the busy port and says so).
- Knobs: `computeThreads` (reproduction needs a small pool; on an unconstrained 16-core box the
  pathology shrinks), `spinMicros` (handler cost), `keys`/`valueSize` (bucket volume),
  `timeoutSec` (global watchdog; defaults to `max(360, warmup*3+30)` and must exceed
  `warmupTimeoutSec + 30`).
- **fetch scenario**: to be added once the fetch-loop consume engine exists — same harness, third
  column in the table above.
