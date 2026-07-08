# BOXP-70: Skip metadata-only sessions without cwd

## Problem

Claude Code can write auxiliary metadata-only JSONL files under
`~/.claude/projects/<slug>/`. These files can contain a `sessionId`
without any `cwd`, for example `ai-title` and `agent-name` records.

`ceeker.watch.sessions` merged those records into a file-state and wrote
the session as `:running` as soon as `:session-id` was available. The
stored record had no usable `:cwd` and no `:pane-id`, so
`ceeker.tmux.pane/stale-session?` never considered it stale because its
main guard required `(seq cwd)`. Since purge only removes terminal
statuses, the record stayed visible forever.

## Design

- Require both non-empty `:session-id` and non-empty `:cwd` before
  `write-session!` writes a parsed JSONL session to the store.
- Keep the accumulated file-state intact. If a later line in the same
  file contains `cwd`, the merged state becomes writable at that point.
- Apply the guard at the shared write path so watcher and scan behavior
  is consistent for Claude, Codex, and pi parsers.
- Treat existing active records with empty `cwd` and empty `pane-id` as
  stale. These are metadata-only ghosts and can be closed by the regular
  stale cleanup cycle, then purged by the existing closed-session TTL.
- Do not mark empty-`cwd` sessions with a `pane-id` as metadata-only
  ghosts, because hook-created sessions can legitimately carry pane
  identity before cwd is available.

## Validation

- Added watcher tests for metadata-only Claude JSONL: it is skipped
  until a later line supplies `cwd`.
- Added scan tests that metadata-only Claude JSONL is not written.
- Added parser coverage that Claude, Codex, and pi sessions without
  `cwd` do not reach the store.
- Added stale tests for empty `cwd` plus missing/present `pane-id`.
- Run `make ci` before commit.
