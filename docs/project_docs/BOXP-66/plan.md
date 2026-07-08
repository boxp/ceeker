# BOXP-66 Session File Watcher

## Goal

Detect Claude Code and Codex sessions from their JSONL session history files so ceeker can show sessions before hook/notify events arrive.

## Design Decisions

- Added `ceeker.watch.sessions` as the single owner of session history parsing, incremental tailing, one-shot scans, pane-id resolution, and WatchService polling.
- Kept WatchService and file reads inside `async/thread` via `start-session-watcher!`; the stop channel is unbuffered and is closed by the caller.
- Used in-memory file offsets for incremental tailing. Each JSONL line is capped at 1 MiB; invalid or oversized lines are ignored.
- One-shot `scan-recent-sessions!` scans JSONL files modified within the last 24 hours by default. It is called by TUI startup and `--list-sessions` before pane/capture refresh.
- Codex `session_meta` establishes `session-id` and `cwd`; later `event_msg` lines update the accumulated per-file session state. `task_complete` maps to `:completed` and stores `last_agent_message`.
- Claude lines with `sessionId`, `cwd`, or `timestamp` map to `:running`; assistant lines also record message content when present.
- Pane-id resolution reuses `ceeker.tmux.pane/list-pane-info` and the new `find-agent-pid-in-tree`. On Linux it reads `/proc/<pid>/environ` for `TMUX_PANE`; otherwise it only falls back when exactly one cwd/agent candidate exists.
- Store normalization now deduplicates entries with the same `:session-id`, preferring pane-id keyed entries and otherwise the newest `:last-updated`.
- Watcher writes skip older same-session data so a stale file event does not overwrite newer hook/capture state.

## Verification

- Parser tests cover Claude and Codex JSONL events.
- Tail tests cover offset-based append reads.
- Pane resolution tests mock tmux and `/proc` access.
- Store tests cover session-id deduplication.
- Scan tests use temporary fixture directories.
- Worker tests verify stop-channel shutdown.
