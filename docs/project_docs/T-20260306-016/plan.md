# T-20260306-016: ceeker startup clean async

## Problem

At TUI startup, `maybe-check-panes!` runs synchronously on tick=0,
calling `close-stale-sessions!` and `refresh-session-states!` before
the first screen render. These functions invoke tmux commands, perform
file I/O, and walk process trees, blocking the UI for hundreds of
milliseconds.

## Solution

### Changes to `src/ceeker/tui/app.clj`

1. Add `bg-check-ref` atom to track background check future
2. Extract `run-pane-check!` with try/catch error handling
3. Modify `maybe-check-panes!` to dispatch checks via `future`
4. Skip new check if prior check is still running (no overlapping)
5. Log errors to stderr without crashing the TUI loop

### Changes to `test/ceeker/tui/app_test.clj`

1. `test-maybe-check-panes-returns-immediately` - verifies startup
   is not blocked by the cleanup
2. `test-maybe-check-panes-skips-when-running` - verifies no
   overlapping concurrent checks
3. `test-maybe-check-panes-continues-after-error` - verifies the
   TUI loop continues after a cleanup failure

## Design Decisions

- **All periodic checks are async**, not just tick=0. This is simpler
  and prevents any pane check from blocking the render loop.
- **Overlap prevention** via `realized?` check on the previous future.
  If a prior check is still running, the next one is skipped.
- **Error handling**: exceptions are caught in `run-pane-check!` and
  logged to `System/err`. The TUI render loop is never affected.
- **State consistency**: cleanup writes to the file-based state store
  atomically under file lock. The next `get-session-list` call in the
  render loop picks up the updated state naturally.

## Non-scope

- No changes to clean logic itself
- No UI design changes
- Hook handler's synchronous `close-stale-sessions!` call is unchanged
  (hook events are short-lived CLI invocations, not TUI startup)
