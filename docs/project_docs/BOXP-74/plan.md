# BOXP-74: Closed TTL and transition log

## Problem

`ceeker.state.store/closed-ttl-ms` is currently 300000 ms. The closed
session retention window is longer than needed for day-to-day visibility,
and there is no durable signal for estimating how often stale detection
temporarily closes sessions that later reactivate.

## Design

- Reduce `closed-ttl-ms` to 60000 ms and update nearby documentation.
- Keep transition logging inside `ceeker.state.store`, next to the three
  state transitions that own the relevant decisions:
  `close-sessions-by-pred!`, `reactivate-closed-session!`, and
  `purge-expired-closed-sessions!`.
- Append one EDN map per event to `<state-dir>/transitions.log` with:
  `:at`, `:event`, `:key`, `:session-id`, and `:agent-type`.
- Write logs while the existing store file lock is held so concurrent
  writers remain serialized with state updates.
- Treat logging as observational only. If rotation or append fails, print
  the exception to `*err*` and continue without changing the store
  function return values or state semantics.
- Rotate before append when `transitions.log` is larger than 1 MiB by
  moving it to `transitions.log.old`. Keep a single old generation.

## Validation

- Add store tests for close, reactivate, and purge log lines.
- Verify log lines read back as EDN maps with the expected shape.
- Add rotation coverage for files larger than 1 MiB.
- Add logging-failure coverage proving the state transition still
  succeeds.
- Run `make ci` before commit.
