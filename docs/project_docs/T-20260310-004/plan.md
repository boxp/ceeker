# T-20260310-004: Stale Session Liveness Fix

## Problem
Sessions remain open after agent termination when the tmux pane persists.
Pane reuse with a different CWD leaves the old session lingering.

## Root Causes

### 1. `find-agent-in-tree` returns `:unknown` for dead processes
When a child process exits between enumeration and cmdline read,
`read-proc-cmdline` returns nil. The code returned `:unknown`
(conservative), preventing stale detection since `:unknown` is
treated as "possibly alive".

### 2. `supersede-old-sessions` only targets `:running` sessions
If a session transitions to `:idle` via capture-pane refresh,
a new session in the same pane does not supersede it because
the filter only checked `(= :running (:agent-status session))`.

### 3. No per-pane dedup in periodic stale check
`session-has-live-agent?` checks if ANY agent of matching type
exists in the pane's process tree. When a new session starts in
the same pane, the old session's liveness check finds the new
agent and considers the old session alive. The supersede mechanism
handles this when hooks fire, but the periodic stale checker had
no fallback.

## Fixes

### Fix 1: `process-alive?` + dead process detection
Added `process-alive?` function that checks `/proc/PID` directory
existence (Linux) or `kill -0` (macOS). `find-agent-in-tree` now
returns `:not-found` (instead of `:unknown`) when the process is
confirmed dead.

### Fix 2: Supersede all active statuses
Changed `supersede-old-sessions` to target `capturable-statuses`
(`:running`, `:idle`, `:waiting`) instead of only `:running`.

### Fix 3: `duplicate-pane-sids` dedup
Added `duplicate-pane-sids` to `close-stale-sessions!`. Before
the process-tree check, it identifies active sessions sharing
the same pane-id and marks all but the newest as stale.

## Why PR #55 / T-20260310-003 didn't fix it
The previous fix correctly:
- Removed CWD from `supersede-key` (now `[pane-id, agent-type]`)
- Added process-tree check to `capture-state-for-closed-session`

But it did NOT address:
- Dead process returning `:unknown` (ephemeral child race)
- `:idle`/`:waiting` sessions escaping supersede
- Periodic stale checker being fooled by a different session's agent

## Test Coverage
- `test-find-agent-dead-process-returns-not-found`
- `test-find-agent-unreadable-process-returns-unknown`
- `test-session-has-live-agent-dead-process`
- `test-session-has-live-agent-alive`
- `test-session-has-live-agent-no-matching-pane`
- `test-stale-closes-older-duplicate-pane-session`
- `test-stale-keeps-sole-live-session`
- `test-supersede-idle-session-closed`
