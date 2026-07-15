# Paced JetStream consumers

A JetStream consume engine that paces pulls by processing speed, added next to the existing
callback engine. New public API: `StreamContext.createOrderedPacedConsumer` /
`getPacedConsumerContext`, `KeyValue.watchPaced` / `watchAllPaced`; the returned contexts and
subscriptions implement the same public traits as the callback engine.

## Problem

The stock jnats consume path is receipt-paced: repulls are issued from the socket reader thread
as messages arrive, regardless of handler speed, so a slow handler accumulates an unbounded
client-side backlog. The only overflow reaction is the dispatcher pending-limit check, which
**silently discards messages on the reader thread**. For ordered consumers every discarded
message is a sequence gap, and jnats reacts to a gap with a synchronous `CONSUMER.CREATE` inside
the message callback — under sustained load this becomes a drop -> recreate -> refetch feedback
loop that can starve the compute pool (the KV-warmup incidents).

## Design

The engine issues one pull at a time and takes the next message only after the handler effect for
the previous one completed. The client-side buffer is therefore bounded by the pull batch, and
drops are impossible by construction: the transport pins the dispatcher's pending limits to
(0, 0) = unlimited, and boundedness comes from the pull budget instead.

```
reader thread ── deliverMessage ──► NatsDispatcher (standard factory path; pendingLimits pinned
                                            │       to 0 by the transport)
                                    exclusive CEMessageHandler: effect = queue.offer(msg), on a
                                            │  per-subscription sequential CE Dispatcher
                                    Queue.unbounded[F, JMessage]
                                            │
                                    ActiveSubscription.next    (take fused with classification:
                                            │                   status interpretation, JetStream
                                            │                   check, consumer-type inspection)
                                    PacedPullEngine loop       (one fiber: masked directive step
                                                                with window timeout, pacing,
                                                                recovery, stop)
```

Key decisions, and why:

- **`exclusiveDefaultHandler` flag on `ConfiguringMessageHandler`** (the only core change). jnats
  allows one `DispatcherFactory` per connection, so the behavior must live inside the installed
  factory; the flag routes every message of a dispatcher to its default handler, bypassing the
  per-subscription jnats wrapper that would otherwise swallow status messages.
- **Push -> pull inversion via `Queue.unbounded` + sequential `Dispatcher[F]`.** jnats delivers by
  pushing; the engine consumes by taking. One sequential loop then owns pacing, ordering,
  recovery and lifecycle — recovery is a return value, never a blocking call inside a callback.
  No parked threads: the loop suspends as a fiber.
- **Classification behind the subscription boundary.** `ActiveSubscription` is plain data:
  consumer name, `pull` handle, `next: Poll[F] => F[Directive]`, `isActive`. The transport fuses
  onto the take: status -> `PullStatusInterpreter` (a pinned 1:1 mirror of jnats
  `PullMessageManager.manageStatus`), non-JetStream message -> `Skip`, data -> consumer-type
  inspection (the ordered gap check). The loop consumes `Deliver/Skip/PullOver/Restart/Fail`
  directives in one match and never sees a raw message.
- **The masked take-to-handle step.** One `F.uncancelable` region per message; the engine's
  `Poll` is applied to the queue wait alone. Once a message is taken, classification, the ordered
  position advance and the handler always run to completion: a window deadline racing a taken
  message can at worst discard the window's *label* (it is counted idle, costing at most one
  liveness probe), never the message — and the position can never advance past an unhandled
  message.
- **Engine dependencies are data and functions** — a `subscribe: Resource[F, ActiveSubscription]`
  (evaluated per (re)subscribe) and a `consumerInfo` lookup; no trait to implement. This keeps
  the loop unit-testable without a NATS server.
- **One value in one Ref per stateful thing.** `SubscriptionState(consumerName, phase)` with
  Running -> Stopping -> Finished: the Ref is created by the first successful subscribe and is
  the payload of the acquisition handshake, so a consumer name always exists in it and finishing
  cannot precede a stop. `OrderedContextState(consumeActive, lastConsumerName, position)`
  persists across consumes, giving sequential-consume continuation and `getConsumerName` between
  consumes.
- **Ordered semantics owned by the ordered subscribe step**: the jnats ordered recipe
  (ack-none, max-deliver 1, memory storage, single replica), the consumer-sequence gap check, and
  recreate-from-last-delivered-sequence on `Restart` — no jnats ordered machinery, so nothing can
  create consumers behind the engine's back.

## Loop and recovery

Three levels, one concern each: `subscribeLoop` (one iteration per subscription lifetime; owns
resubscribe and progressive backoff, 100ms..5s), `pullLoop` (one iteration per pull request; owns
empty-window accounting and the consumer liveness probe after 2 consecutive idle windows),
`drainWindow` (one iteration per message, paced by the handler). Recoveries: ordered sequence gap
-> immediate resubscribe continuing after the last delivered stream sequence; consumer deleted
(error status or failed liveness probe) -> resubscribe; subscription inactive -> resubscribe
after a short delay; handler failure -> reported, processing continues (matches the callback
engine). Acquisition of `consume` blocks (cancelably) until the first subscribe succeeds, or
fails with the last error after 5 attempts (~1.5 s of backoff) - like the callback engine on
permanent configuration errors, while still absorbing transient ones; after the first success,
resubscribes retry indefinitely.

`stop` lets the in-flight message finish and the loop exit at the next window boundary; `close`
is prompt while waiting but also lets an in-flight message finish (the step is masked). A pull
terminus arriving well before the window deadline is guarded with a 500ms sleep so terminus
storms cannot re-pull in a hot loop; the guard self-disables for sub-second windows.

## Observability

`PacedConsumerListener` (subscribed / resubscribing / failed / pullIssued / messageProcessed /
handlerFailed) for metrics and alerting; engine and handler errors are additionally reported to
the jnats connection `ErrorListener`, so existing log wiring works without a custom listener.

## Testing

- `PacedPullEngineSpec` — the loop against a scripted directive queue, no server: pacing and
  batch-scaled pulls, gap restart, terminus/informational windows, error retry with backoff,
  stop/close lifecycle, first-subscribe retry, and the two mask pins (deadline mid-handler does
  not interrupt; close waits for the in-flight message).
- `PullStatusInterpreterSpec` — pins the status table so a jnats upgrade that shifts semantics
  fails loudly (the most upgrade-sensitive piece); `BufferedPullTransportSpec` pins the JetStream
  verification in the classification.
- `PacedOrderedConsumerContextSpec` / `PacedConsumerContextSpec` / `PacedKeyValueSpec` —
  server-backed behavior of the public API: warmup, recovery after consumer deletion, sequential
  continuation, stop, prompt idle release, handler failure, durable ack/redelivery, concurrent
  consumes, KV watching.
- `PacedStressSpec` — the behavioral bar: 8 watchers x 1500 keys with a slow handler; zero drops,
  zero consumer recreations, strict revision order, pulls bounded by the batch budget.

## Known limitations

- Cancelling `consume` while `js.subscribe` is in flight can leave an ordered ephemeral consumer
  server-side until its `inactiveThreshold` (2x pull window, min 5 minutes) reaps it — bounded
  and self-healing; a best-effort `deleteConsumer` on that path is the first productionization
  item.
- A reconnect voids outstanding pull requests server-side; the loop only notices at the client
  window deadline, so message flow can stall up to one pull window (default 30s) after a
  reconnect. Shorter `ConsumeOptions.expiresIn` bounds the stall at the cost of more pull
  traffic when idle.
- Not built: overlapping/prefetch pulls (`ConsumeOptions.thresholdPercent` is accepted and
  ignored), paced variants of `keys`/`keysDetailed`/`history`/`consumeKeys`.
