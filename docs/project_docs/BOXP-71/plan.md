# BOXP-71: Stop watcher rewrites of closed headless sessions

## Problem

Headless Claude Code sessions can keep appending JSONL after ceeker has
closed their store entry. The watcher MODIFY path rebuilt the file state
from the appended line and unconditionally merged it into the store via
`write-session!`.

That caused this loop:

1. JSONL append produces an active `:running` parsed session.
2. Watcher writes it over the existing `:closed` store entry and updates
   `:last-updated`.
3. Stale cleanup sees no live agent in a matching tmux pane and closes it
   again, also refreshing `:last-updated`.
4. The next append repeats the cycle.

Because terminal purge uses `:last-updated` and `store/closed-ttl-ms`,
the TTL never elapsed and the closed pane-less session stayed visible.

## Design

- Keep the existing scan liveness policy and share it with watcher writes
  for new active pane-less sessions: skip only when liveness is exactly
  `:dead`; write on `:alive`, `:unknown`, or tmux unavailable.
- When watcher sees an existing terminal store entry, do not merge over
  it by default.
- Reactivate only existing `:closed` sessions, only when the parsed
  watcher state is capturable and `pane/session-has-live-agent?` returns
  `:alive`.
- Leave existing `:completed` and `:error` sessions untouched from the
  watcher path. This matches the current store semantics where
  `store/reactivate-closed-session!` is intentionally `:closed`-only.
- Preserve existing active-session watcher updates when the store entry
  is already active and the incoming timestamp is not older.

## Pane Info Reuse

Watch MODIFY events can arrive in bursts. `poll-watch-once!` now creates
one delayed `pane/list-pane-info` value per WatchService batch and passes
it through `process-lines!` to the shared liveness gate. The delay means
the tmux call is avoided when no liveness check is needed, and reused
when multiple events in the same batch need it.

`resolve-pane-id` still owns its existing pane lookup and `/proc`
environment check. That path is separate from the liveness decision and
was left unchanged to keep this fix scoped.

## Validation

- Added watcher MODIFY tests for existing `:closed` entries with
  `:dead` and `:alive` liveness.
- Added watcher MODIFY coverage that existing `:completed` entries are
  not overwritten.
- Added watcher MODIFY tests for new active sessions with `:dead`,
  `:alive`, and `:unknown` liveness.
- Kept scan behavior covered by existing tests.
- Run `make ci` before commit.
