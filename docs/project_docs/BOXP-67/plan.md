# BOXP-67 pi agents support plan

## Scope

- Add pi session-file discovery from `~/.pi/agent/sessions`.
- Parse pi JSONL session headers and assistant messages into ceeker sessions.
- Add pi process liveness matching for scan and stale cleanup.
- Add pi capture-pane state detection, TUI filtering/display, list output, and hook command normalization.
- Update README documentation and verify with `make ci`.

## Design decisions

- pi session paths use a cwd slug format different from Claude Code, but the first JSONL line contains the exact `cwd`; ceeker will parse `cwd` from the session header and will not decode the slug.
- pi JSONL has no explicit Codex-like `task_complete` event. The watcher will only write pi sessions as `:running`; waiting, idle, and closed transitions remain capture-pane and liveness/stale responsibilities.
- pi assistant `message.content` can be a string or content block array. ceeker will concatenate text-like blocks for `:last-message` and ignore non-text blocks.
- The pi process is a Node process running the `pi` CLI path. The liveness regex will match command path/argv token `pi` with boundaries, e.g. `(^|/|\\s)pi(\\s|$)`, to avoid matching `pip`, `pipe`, Claude, or Codex.
- The default unknown-agent process pattern will remain Claude/Codex only. Unknown agent sessions are legacy/defensive paths; adding pi there would make a pane running pi keep an unrelated unknown session alive. pi sessions parsed by the watcher and hooks have explicit `:agent-type :pi`, so scan and stale paths use the pi-specific pattern.
- pi TUI documentation exposes a configurable streaming working indicator and says custom frames may be arbitrary. Because a stable default spinner string is not exposed as a contract and confirmation UI can be extension-defined, ceeker will use conservative pi capture detection: running from documented working/queue/abort cues and idle/waiting from existing generic prompt/dialog patterns. This avoids fragile coupling to private rendering details.

## Test plan

- Parser unit tests for pi `session` and assistant `message` lines.
- Scan liveness tests ensuring pi sessions are kept only when the pi process is live or unknown, and skipped when dead.
- Stale cleanup tests ensuring pane-id-less pi sessions use the pi process matcher.
- `agent-pattern` tests for pi matches and false positives (`pip`, `pipe`, `claude`, `codex`).
- Capture tests for pi running and prompt/dialog fallback.
- TUI filter cycle and badge tests for pi.
- `--list-sessions` external JSON test for `agent_type: "pi"`.
- Hook normalize test for `ceeker hook pi <event>`.

## Verification

- Run focused tests while developing.
- Run `make ci` before committing.
