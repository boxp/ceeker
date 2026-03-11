# ceeker pane-centric state handling redesign

## Why pane-centric?

ceeker's core purpose is "list panes running AI sessions, jump to them instantly."
The current session-id based design causes complexity when resume/subagent/nested agents
create multiple session-ids within the same pane, requiring supersede logic, merge guards,
and dedup cleanup. By making pane-id the unique key, all these problems vanish.

## Design

### Store key strategy
- **With pane-id**: Use pane-id as the store key. Any hook from the same pane
  overwrites the same entry. No supersede needed.
- **Without pane-id** (outside tmux): Use session-id as fallback key.

### Removed concepts
- `supersede-key`, `supersede-old-sessions`, `should-supersede?`, `maybe-supersede`
- `superseded?` flag and `merge-session-data` guard logic
- `close-dup-pane-sessions!`, `duplicate-pane-sids`, `older-dup-sids`, `close-sessions-by-ids`
- Session-id display in TUI (both table and card views)
- SESSION column in table header

### Simplified logic
- `update-session!`: Read state, merge new data into existing entry at key, write back.
  No supersede check needed.
- `reactivate-closed-session!`: Remove superseded guard. Just check `:closed` status.
- `expired-terminal?` / `purgeable?`: Remove superseded exclusion. All expired terminal
  sessions are eligible for purge.
- `handler/handle-hook!`: Compute store key as `(if (seq pane-id) pane-id session-id)`,
  pass to `store/update-session!`.

### View changes
- Table: Remove SESSION column, keep AGENT/STATUS/WORKTREE/MESSAGE/UPDATED
- Card: Remove session-id from header line
- Filter search: Search by cwd and pane-id instead of session-id

### Preserved behavior
- Different panes = different entries (parallel execution preserved)
- Stale session detection (cwd + process tree check)
- Capture-pane state refresh
- Purge of expired closed sessions

## Test plan
1. Same pane-id + different session-ids = 1 entry (updated)
2. Same pane-id + different cwds = same entry (updated)
3. Different pane-ids = separate entries
4. No session-id in display output
5. Regression: stale detection, purge, reactivation all work
