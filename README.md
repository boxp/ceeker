# ceeker

> [日本語版 (Japanese)](./README.ja.md)

A TUI for monitoring AI coding agent sessions and progress across tmux panes.

In environments where multiple AI coding agents (Claude Code / Codex / pi) run in parallel, ceeker provides a unified view of all sessions with the ability to jump to individual tmux panes.

![ceeker screenshot](./assets/ceeker-screenshot.png)

## Why ceeker?

- **Works on Windows (WSL), Linux, and macOS**
- **Supports Claude Code, Codex, and pi**
- **Jump to the target Claude Code / Codex / pi pane just by pressing `Enter`**
- **Monitor multiple agent sessions in one place**

## Prerequisites

- tmux

## Installation

### One-liner install

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh
```

Supported platforms: `darwin-arm64`, `linux-amd64`, `linux-arm64`

This installer downloads the matching release tarball from GitHub Releases, verifies it against `checksums.txt`, and installs `ceeker` to `~/.local/bin` by default.

Custom install directory:

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh -s -- -b ~/.local/bin
```

Install a specific version:

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh -s -- -v 0.1.0
```

Unsupported platforms should use Homebrew or the manual tarball installation below.

### Homebrew (macOS / Linux)

```bash
brew tap boxp/tap
brew install ceeker
```

To update:

```bash
brew update
brew upgrade ceeker
```

### Install from tarball

Download the tarball for your platform from [Releases](https://github.com/boxp/ceeker/releases):

```bash
# Example: macOS ARM64
curl -L -o ceeker.tar.gz https://github.com/boxp/ceeker/releases/latest/download/ceeker-darwin-arm64.tar.gz
tar xzf ceeker.tar.gz
chmod +x ceeker-darwin-arm64
sudo mv ceeker-darwin-arm64 /usr/local/bin/ceeker
```

```bash
# Example: Linux amd64
curl -L -o ceeker.tar.gz https://github.com/boxp/ceeker/releases/latest/download/ceeker-linux-amd64.tar.gz
tar xzf ceeker.tar.gz
chmod +x ceeker-linux-amd64
sudo mv ceeker-linux-amd64 /usr/local/bin/ceeker
```

## Usage

### TUI

```bash
ceeker
```

Displays a list of all active sessions.

**Features:**

- **Auto-refresh**: Detects file changes to `sessions.edn` via inotify (Linux) / WatchService and automatically updates the TUI
- **Session file watcher**: Detects Claude Code / Codex / pi sessions from history JSONL files even without hooks
- **Session filtering**: Filter the display by agent type, status, or text search

**Key bindings:**

| Key | Action |
|-----|--------|
| `j` / `↓` | Move down |
| `k` / `↑` | Move up |
| `Enter` | Jump to the selected session's tmux pane |
| `r` | Manual refresh |
| `v` | Toggle view mode (Auto → Table → Card) |
| `a` | Toggle agent type filter (All → Claude → Codex → Pi → All) |
| `s` | Toggle status filter (All → running → completed → error → waiting → idle → All) |
| `/` | Text search (partial match on session-id / cwd) |
| `c` | Clear all filters |
| `q` | Quit |

### Session List JSON

With `--list-sessions`, ceeker skips the TUI and prints the current session list as JSON. This is intended for LLM or tool integration. Each session includes `pane_id` so callers can identify the tmux pane directly.

```bash
ceeker --list-sessions
```

Example output:

```json
[
  {
    "session_id": "sess-123",
    "agent_type": "codex",
    "agent_status": "running",
    "cwd": "/path/to/worktree",
    "pane_id": "%42",
    "last_message": "planning changes",
    "last_updated": "2026-04-02T12:34:56Z"
  }
]
```

Before printing, ceeker scans recent session history files, then performs one synchronous pane liveness and capture-based state refresh. If tmux refresh fails, ceeker still returns the stored session list.

### Exit on Jump

With `--exit-on-jump`, ceeker exits automatically after a successful jump. This is useful when running ceeker as a one-shot popup — select a session, jump, and the popup closes by itself.

```bash
ceeker --exit-on-jump
```

### Startup View

With `--view`, you can choose the initial layout at startup. Supported values are `auto`, `table`, and `card`.

```bash
ceeker --view table
ceeker --view card
```

## Handy tmux configuration

You can open ceeker as a popup from anywhere inside tmux. Combine with `--exit-on-jump` so the popup closes automatically after you select a pane.

```tmux
# Show a popup with all Claude Code / Codex / pi states via prefix + C-k
bind-key C-k display-popup -h 80% -w 80% -d "#{pane_current_path}" -E "ceeker --exit-on-jump"
```

## Setup

ceeker automatically detects Claude Code, Codex, and pi sessions by watching their session history JSONL files:

- Claude Code: `~/.claude/projects/<cwd-slug>/<session-id>.jsonl`
- Codex: `~/.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl`
- pi: `~/.pi/agent/sessions/<cwd-slug>/<timestamp>_<uuid>.jsonl`

This means sessions can appear in ceeker without hook configuration. Codex and pi sessions are tracked from the session file alone, so Codex `notify` is optional and pi does not need ceeker-specific setup.

Hooks are still useful for richer event timing and final messages, especially if you want explicit Claude Code hook events or Codex notify updates. `ceeker hook pi <event>` is accepted for future pi extension integrations, but it is not required for automatic pi discovery.

### Claude Code

Add the following to `.claude/settings.json` (using the 3-level nesting format per the [official hooks reference](https://code.claude.com/docs/en/hooks)).

For ceeker's metrics-only use case, command hooks are configured with `"async": true` so they run in the background and do not block the agent loop.

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude SessionStart",
            "async": true
          }
        ]
      }
    ],
    "Notification": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude Notification",
            "async": true
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude PreToolUse",
            "async": true
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude PostToolUse",
            "async": true
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude Stop",
            "async": true
          }
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude SubagentStop",
            "async": true
          }
        ]
      }
    ]
  }
}
```

Claude Code passes a JSON payload to command hooks via stdin. The payload contains common fields such as `session_id`, `cwd`, and `hook_event_name` (see the [hooks reference](https://code.claude.com/docs/en/hooks)). For `Stop` and `SubagentStop`, ceeker also captures `last_assistant_message` and shows it as the session's `last-message`.

Note: `InstructionsLoaded` is an event that is already asynchronous by design on the Claude Code side.

### Codex (notify — recommended)

Use Codex's `notify` mechanism. Add the following to `~/.codex/config.toml`:

```toml
notify = ["ceeker", "hook", "codex"]
```

Codex appends the JSON payload as the last argument of the `notify` command (via argv, not stdin).

### Codex (hooks — not recommended, v0.114.0+)

Codex [v0.114.0+](https://github.com/openai/codex/releases/tag/rust-v0.114.0) supports `SessionStart` and `Stop` events via its **experimental** hooks engine.

> **Not recommended:** As of Codex v0.142.5, the hooks engine does not support async execution. Hooks run synchronously regardless of the `"async"` value in `hooks.json`, which can significantly reduce Codex responsiveness. Use `notify` above for now. Reconsider hooks after Codex adds async hooks support.
>
> The `codex_hooks` feature is currently **experimental** and its API may change in future releases.

#### 1. Enable the feature flag

The hooks engine is gated behind a feature flag. Enable it **before** adding `hooks.json`:

```bash
codex features enable codex_hooks
```

Or add the following to `~/.codex/config.toml`:

```toml
[features]
codex_hooks = true
```

> Without this flag, `hooks.json` will be ignored and no hook events will fire.

#### 2. Add `~/.codex/hooks.json`

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook codex SessionStart",
            "async": false
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook codex Stop",
            "async": false
          }
        ]
      }
    ]
  }
}
```

Codex hooks pass a JSON payload via stdin (same as Claude Code). The payload contains `session_id`, `cwd`, `hook_event_name`, `model`, `permission_mode`, and `transcript_path`. For `SessionStart`, `source` indicates whether the session was started fresh (`"startup"`) or resumed (`"resume"`). For `Stop`, ceeker also captures `last_assistant_message`.

Because async hooks are not currently supported by Codex, this setup is not recommended for day-to-day use. If both `hooks.json` and `notify` are active, ceeker will receive duplicate events.

#### Troubleshooting — Codex hooks

| Symptom | Cause | Fix |
|---------|-------|-----|
| No session appears in ceeker after starting Codex | Feature flag `codex_hooks` is not enabled | Run `codex features enable codex_hooks` or add `[features] codex_hooks = true` to `~/.codex/config.toml` |
| Codex becomes noticeably less responsive | Codex currently runs hooks synchronously regardless of the `"async"` value | Use `notify` instead of hooks until Codex supports async hooks |
| Duplicate session events | Both `hooks.json` and `notify` in `config.toml` are active | Remove the `notify` line from `config.toml` |

## Automatic Session Cleanup

When a tmux pane is closed, the corresponding session automatically transitions to the `Closed` state.

**Check timing:**

- All sessions are checked at TUI startup
- Periodic checks run approximately every 10 seconds while the TUI is displayed
- Checks also run when a hook event is received

**How it works:**

A single `tmux list-panes -a` call retrieves the cwd and PID of all panes, which are then matched against sessions in the `running` state. A session transitions to `closed` under the following conditions:

1. **Pane not found**: No tmux pane exists matching the session's cwd
2. **Process tree search**: Even if a pane with a matching cwd exists, the target agent (claude/codex/pi) process is not found in the pane's process tree

Checks are skipped when tmux is not available.

### Session Deduplication (Supersede-per-Key)

Prevents stale sessions from accumulating when an agent is closed and resumed within the same tmux pane.

**Behavior:**

- On hook event receipt, the pane ID is obtained from the `$TMUX_PANE` environment variable
- When a new session is registered, any existing `running` session with the same key `(pane-id, agent-type, cwd)` is automatically transitioned to `closed` (superseded)
- If `$TMUX_PANE` is not available (e.g., outside tmux), supersede detection is skipped

**Example:**

1. Start Claude Code in pane `%42` → session-A becomes `running`
2. Close Claude Code → session-A remains `running` (if the Stop hook was not delivered)
3. Resume in the same pane `%42` → session-A is automatically set to `closed` when session-B is registered

## Display Modes for Narrow Panes

When the terminal width is less than 80 columns, the display automatically switches to a compact card layout.

### View Modes

| Mode | Description |
|------|-------------|
| Auto | Card below 80 columns, table at 80+ (default) |
| Table | Always show table view |
| Card | Always show card view |

Press `v` to cycle through Auto → Table → Card.

### Card View Example

```
  ceeker — 2 session(s)
  ────────────────────────────────
  ┌ abc123 [Claude] ● Running
  │ 12:34:56  my-project
  │ Working on feature...
  └─
  ┌ xyz789 [Codex] ○ Done
  │ 12:30:00  backend
  │ Completed refactoring
  └─
  ────────────────────────────────
  [j/k] Navigate  [Enter] Jump to tmux  [r] Refresh  [v] View:Auto  [q] Quit
```

### Table View Example (Normal Width)

```
  ceeker — 2 session(s)
  ────────────────────────────────────────────────────────────────────────────────
   SESSION      AGENT     STATUS      WORKTREE     MESSAGE                                  UPDATED
  ────────────────────────────────────────────────────────────────────────────────
   abc123       [Claude]  ● Running   my-project   Working on feature...                    12:34:56
   xyz789       [Codex]   ○ Done      backend      Completed refactoring                    12:30:00
  ────────────────────────────────────────────────────────────────────────────────
  [j/k] Navigate  [Enter] Jump to tmux  [r] Refresh  [v] View:Auto  [q] Quit
```

## Development

```bash
# Run tests
make test

# Lint
make lint

# Format
make format
```

The ceeker repo ships repo-local hooks for both Claude Code and Codex.

- `.claude/settings.json`: runs `scripts/agent-hooks/lint_format_check_hook.clj` asynchronously after `PostToolUse` for `Write|Edit|MultiEdit|Bash`
- `.codex/hooks.json`: runs the same script after `PostToolUse`

The hook is implemented as a babashka (`bb`) script. It runs `make format-check` and `make lint` in sequence and reports the result back to the agent. Claude Code picks this up automatically when you open the repo. For Codex, ensure the feature flag is enabled by adding `[features] codex_hooks = true` to `~/.codex/config.toml` or by running `codex features enable codex_hooks`.

Note: per the official Codex hooks documentation as of April 14, 2026, `PostToolUse` currently fires only for `Bash`. ceeker therefore limits the Codex hook to Bash commands that are likely to have modified the workspace before running `format-check` and `lint`.

## CI

GitHub Actions runs the following jobs on PRs and pushes to main:

- **lint**: clj-kondo lint + cljfmt format-check
- **test**: Clojure unit tests
- **native-e2e**: GraalVM native-image build + E2E tests

### native-e2e

Runs E2E tests against a binary built with native-image to catch native-image-specific issues that don't reproduce on the JVM.

Test cases:
- `--help` output verification
- Hook commands (Claude / Codex / pi) session recording
- TUI startup and exit (`q` key)
- TUI search mode (`/` → `Esc` → `q`)

TUI tests use tmux to simulate a terminal.

## License

MIT
