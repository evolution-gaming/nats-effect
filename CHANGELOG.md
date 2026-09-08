# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- The paced consume engine (`KeyValue.watchPaced` / `watchAllPaced`, `StreamContext.createOrderedPacedConsumer` /
  `getPacedConsumerContext`) mistook the server's routine expiry of an idle pull for an early pull terminus: the local window deadline
  (armed at publish) always fired before the server's `expiresIn` clock (started at receipt), so the server's `408` landed at the head of
  the next window and tripped the 500 ms early-terminus guard — a non-draining sleep and a redundant second pull per idle window.
  Statuses are now matched to the pull they answer via jnats's per-pull reply subjects, and stale ones are skipped. No API or
  configuration change; the callback engine is unaffected.

## [1.3.1] - 2026-07-28

### Added

- Pure KV key validation on the `KeyValue` companion object: `validateKey` (write/read keys) and
  `validateKeyWildcardAllowed` (`watch`/`keys` filter patterns), returning `Either[InvalidKeyError, Unit]` with the
  offending key and the jnats reason. Both delegate to the jnats validators (KV key grammar plus strict subject
  grammar), so callers can validate keys before a write instead of catching and sniffing `IllegalArgumentException`
  from `put`.

## [1.3.0] - 2026-07-23

### Added

- `KeyValue.keysDetailed(...)` (three overloads mirroring `keys`) returning `KeysResult(keys, warmup)`, which exposes
  the `Warmup.Result` of the underlying warmup. This lets callers detect when a key listing was cut short by the
  warmup `timeout` (`Warmup.Result.Timeout`) versus completed fully (`Warmup.Result.Success`).

### Fixed

- `KeyValue.keys(...)` could silently return a **partial** key set when warmup hit its `timeout` before all pending
  messages were drained, with no signal to the caller — a silent-data-loss hazard for any `list`-style operation built
  on `keys`. Fixed additively (non-breaking): `keys` behaviour is unchanged and it now delegates to `keysDetailed`;
  callers that need completeness guarantees should switch to `keysDetailed` and inspect `warmup` (e.g. raise, retry with
  a longer timeout, or return partial-with-a-flag). No existing caller breaks. See Option A in `TASK.md`.
