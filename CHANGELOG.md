# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
