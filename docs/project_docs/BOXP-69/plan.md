# BOXP-69: session-file watcher scan liveness fixes

## Goal

Fix two regressions introduced by the session history file watcher:

- Startup one-shot scans should not repopulate stale sessions that are already terminal past the closed-session TTL.
- Startup one-shot scans should not repopulate active-looking sessions when tmux is available and no matching agent process is alive.
- Pane-id-less sessions whose cwd matches a live tmux pane should not stay visible forever when the pane has no matching agent process.

## Plan

1. Add tests before implementation:
   - scan skips terminal sessions older than `store/closed-ttl-ms`
   - scan keeps terminal sessions within `store/closed-ttl-ms`
   - scan skips active sessions when pane-id resolution fails and no agent process is found
   - scan keeps active sessions when pane-id resolution fails but a cwd-matching pane has a matching agent process
   - scan keeps active sessions when tmux is unavailable
   - watch-event processing does not apply the scan-only liveness check
   - `stale-session?` treats pane-id-less cwd matches with dead agents as stale
   - `stale-session?` keeps pane-id-less cwd matches non-stale when liveness is unknown
2. Limit the new scan liveness rules to `scan-recent-sessions!` by passing an explicit scan option through `process-lines!` / `write-session!`.
3. Keep watch MODIFY/CREATE event writes on the existing path without the scan-only liveness filter, so event processing does not add per-event process-tree cost.
4. Update `stale-session?` so the pane-id-less + cwd-present branch delegates to `session-has-live-agent?`.
5. Run focused tests, formatting/lint, full `make ci`, then commit with an English logical commit message.

## Non-tmux session impact

The startup scan preserves non-tmux behavior when tmux is unavailable: `pane/list-pane-info` returning nil allows active sessions to be written as before.

For stale cleanup, pane-id-less non-tmux sessions only reach the changed branch when their cwd matches a currently live tmux pane cwd. If no pane cwd matches, they still follow the existing `(not cwd-present?)` branch and are closed. When the cwd does match a live pane, the previous behavior could keep a dead watcher-created session visible forever. Delegating to `session-has-live-agent?` improves that case by closing only when the cwd-matching pane's process tree is definitely dead for the agent; `:unknown` remains non-stale, preserving conservative behavior.

## Review follow-up: scan unknown liveness

Codex review pointed out that startup scan still used `find-agent-pid-in-tree`, which collapses both definitely dead agents and unreadable process information to nil. That made scan stricter than stale cleanup: an active session whose matching tmux pane had unknown process-tree liveness could be skipped even though cleanup treats unknown as non-stale.

The follow-up keeps one shared liveness policy by making `ceeker.tmux.pane/session-has-live-agent?` public and using it from `ceeker.watch.sessions`. Scan now skips active pane-id-less sessions only when liveness is exactly `:dead`; `:alive` and `:unknown` are both written. Tests cover the new `:unknown` path and retain the existing `:dead` skip behavior.
