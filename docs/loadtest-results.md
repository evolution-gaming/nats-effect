# Load test results: KV warm-up collapse — callback vs paced engine

*Date: 2026-07-15. Produced by the `loadtest` module (see the README "Load testing" section).
Workload: 20 KV watchers started simultaneously against a bucket of 18,000 keys × 12 KiB = 211 MiB,
production-fidelity handler (per-entry cache update + ~5 µs spin), compute pool restricted to
2 threads, 60 s warm-up timeout. Apple M3 Max, OpenJDK 21, `-Xmx4g`, embedded nats-server 2.12.1,
jnats 2.25.1. Both columns measured back-to-back in one session on the #12 branch (rebased on
master).*

## Comparison

| Metric | callback engine (current `master`) | paced engine (#12) |
|---|---|---|
| Warm-ups (of 20) | 16 success / **4 timeout** | **20 success** |
| Warm-up times (min / median / max) | 18.7 / 51.5 / 59.5 s | **1.9 / 2.1 / 2.2 s** |
| Consume wall time | 60.0 s (timeout-bound) | **2.2 s** |
| Messages handled (of 360,000) | 294,875 | **360,000** |
| Throughput | 4,912 msg/s (57.6 MiB/s) | **163,339 msg/s (1.9 GiB/s)** |
| Dropped messages / slow-consumer events | 0 / 0 | 0 / 0 |
| Consumer recreations (sampled lower bound) | **≥16** | **0** |
| Liveness probe lag (p99 / max) | 21 ms / **12,065 ms — DEAD** | 32 / 32 ms |
| Peak heap (for 211 MiB of data) | **3,737 MiB** | **675 MiB** |
| Verdict | PROBLEM REPRODUCED | CLEAN |

The callback engine is receipt-paced: pulls are replenished as messages arrive, so the burst
buffers client-side without bound (3.7 GiB), starves the 2-thread compute pool (12 s liveness
stall, heartbeat-driven consumer recreations, warm-up timeouts). The paced engine couples pulls to
processing: the buffer stays bounded by one pull batch, so drops and recreations are impossible by
construction, and the same workload completes ~27× faster.

Raw `RESULT_JSON` lines:

```json
{"scenario": "unlimited", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 762, "consumeMillis": 60027, "warmupSuccess": 16, "warmupTimeout": 4, "watcherFailures": 0, "messagesHandled": 294875, "msgPerSec": 4912, "dropped": 0, "slowConsumers": 0, "recreations": 16, "errors": 0, "exceptions": 1, "outgoingDiscards": 0, "livenessLagP99Ms": 21, "livenessLagMaxMs": 12065, "peakHeapMiB": 3737, "reproduced": true}
{"scenario": "paced", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 772, "consumeMillis": 2204, "warmupSuccess": 20, "warmupTimeout": 0, "watcherFailures": 0, "messagesHandled": 360000, "msgPerSec": 163339, "dropped": 0, "slowConsumers": 0, "recreations": 0, "errors": 0, "exceptions": 0, "outgoingDiscards": 0, "livenessLagP99Ms": 32, "livenessLagMaxMs": 32, "peakHeapMiB": 675, "reproduced": false}
```

## Reproducing

```bash
sbt 'loadtest/run scenario=unlimited watchers=20 keys=18000 valueSize=12288 warmupTimeoutSec=60 computeThreads=2'
sbt 'loadtest/run scenario=paced     watchers=20 keys=18000 valueSize=12288 warmupTimeoutSec=60 computeThreads=2'
```

Exit codes: 0 = finished (see verdict), 1 = bad args, 2 = watchdog kill (thread dump printed),
3 = crash. Reproduction of the callback pathology needs the constrained pool (`computeThreads=2`).

## Legacy results (pre-#10 master, 2026-07-14)

Recorded before #10 hardcoded unlimited pending limits into every JetStream dispatcher; the
**baseline** configuration (jnats default limits, 512 Ki msgs / 64 MiB) is no longer reachable via
the public API and reproduced the original incident's client-side drops. Preserved as recorded:

| Metric | baseline (default limits) | unlimited (0,0) |
|---|---|---|
| Warm-ups (of 20) | 11 ok / 4 timeout / 5 failed | 9 ok / 7 timeout / 4 failed |
| Dropped messages (consume phase) | **3,581** | 0 |
| Slow-consumer events | **243** | 0 |
| Consumer recreations (sampled lower bound) | ≥17 | ≥9 |
| Watcher failures (JS control-plane timeout) | 5 | 4 |
| Liveness probe max lag | 10,062 ms — DEAD | 4,143 ms |
| Peak heap | 1,445 MiB | 1,705 MiB |
| Verdict | PROBLEM REPRODUCED | PROBLEM REPRODUCED |

```json
{"scenario": "baseline", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 690, "consumeMillis": 60025, "warmupSuccess": 11, "warmupTimeout": 4, "watcherFailures": 5, "messagesHandled": 235881, "msgPerSec": 3930, "dropped": 3581, "slowConsumers": 243, "recreations": 17, "errors": 0, "exceptions": 1, "outgoingDiscards": 0, "livenessLagP99Ms": 17, "livenessLagMaxMs": 10062, "peakHeapMiB": 1445, "reproduced": true}
{"scenario": "unlimited", "watchers": 20, "keys": 18000, "valueSizeBytes": 12288, "computeThreads": 2, "spinMicros": 5, "populateMillis": 738, "consumeMillis": 60030, "warmupSuccess": 9, "warmupTimeout": 7, "watcherFailures": 4, "messagesHandled": 168026, "msgPerSec": 2799, "dropped": 0, "slowConsumers": 0, "recreations": 9, "errors": 0, "exceptions": 1, "outgoingDiscards": 0, "livenessLagP99Ms": 6, "livenessLagMaxMs": 4143, "peakHeapMiB": 1705, "reproduced": true}
```

Legacy takeaway: default pending limits produced drops → sequence gaps → the recreation feedback
loop; unlimited limits removed the drops but kept the overload (control-plane starvation, unbounded
memory). Pending-limit tuning changed *which* pathology occurred, not *whether* the system
degraded — the paced engine above is the structural fix.
