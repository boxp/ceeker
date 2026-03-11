# ceeker resume duplicate session fix

## Root Cause

`maybe-supersede` in `store.clj` only fires for brand-new session IDs
(`(not (contains? sessions session-id))`). When Claude Code resumes a
past session via `--resume`, the hook sends the same session-id that
already exists in the store. Because the session-id is already known,
supersede is skipped, leaving the previously-active session in the same
pane un-superseded. Both sessions then appear as active in ceeker.

## Fix

Changed `maybe-supersede` to also fire when an existing session
reactivates from a terminal state (`:completed`, `:closed`, `:error`).
This covers the resume case where session-id is reused while still
avoiding spurious supersede on normal ongoing hook updates (where the
session is already `:running`/`:idle`/`:waiting`).

Moved `terminal-statuses` definition above `maybe-supersede` to fix
forward-reference lint error.

## Files Changed

- `src/ceeker/state/store.clj` — `maybe-supersede` logic + def ordering
- `test/ceeker/state/store_test.clj` — 5 regression tests added

## Tests Added

1. `test-resume-supersedes-active-session-in-same-pane`
2. `test-resume-with-different-cwd-supersedes`
3. `test-resume-does-not-affect-different-pane`
4. `test-resume-closed-session-supersedes`
5. `test-ongoing-running-update-no-supersede`
