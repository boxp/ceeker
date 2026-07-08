# BOXP-65 Hook Fast Path

## Plan

- Add a regression test that `handle-hook!` does not run stale session cleanup.
- Keep stale cleanup covered through TUI pane checker and `--list-sessions` refresh paths.
- Remove `close-stale-sessions!` from the hook handler so hook work is limited to payload parse, normalization, and store write.
- Update English and Japanese README Codex guidance to recommend `notify` and mark Codex hooks as not recommended until async hooks are supported.

## Decisions

- Hook path must stay O(1) with respect to tmux panes and stored sessions.
- Stale cleanup remains in `start-pane-checker!` and `session-list/refresh-session-state!`.
- Codex hooks documentation keeps the setup for future reference, but day-to-day guidance points to `notify` because Codex currently executes hooks synchronously even with `"async": true`.
