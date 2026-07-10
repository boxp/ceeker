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

For normal tmux use, no agent configuration is required. After installing ceeker,
start Claude Code, Codex, or pi in tmux and run:

```bash
ceeker
```

ceeker automatically detects sessions by watching their history JSONL files:

- Claude Code: `~/.claude/projects/<cwd-slug>/<session-id>.jsonl`
- Codex: `~/.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl`
- pi: `~/.pi/agent/sessions/<cwd-slug>/<timestamp>_<uuid>.jsonl`

The watcher also reads the latest assistant message from Claude Code and pi
assistant entries and from Codex `task_complete.last_agent_message`. Session
discovery, status updates, and `last-message` therefore do not depend on hooks or
Codex `notify`.

ceeker matches a session to a pane using its working directory, the agent process,
and the process's `TMUX_PANE` when available. If the same agent type runs in
multiple panes with the same working directory and pane resolution is ambiguous,
the session can still be listed without a jump target. The optional integrations
below can provide an explicit pane ID in that case.

### Optional agent integration

Do not add these settings for ordinary tmux use. They are only useful to:

- register a session that runs outside tmux, or
- improve pane binding when automatic resolution is ambiguous.

#### Claude Code (optional)

If one of those cases applies, add only asynchronous `SessionStart` and `Stop`
hooks to `.claude/settings.json` using the format from the
[official hooks reference](https://code.claude.com/docs/en/hooks):

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
    ]
  }
}
```

Claude Code passes the hook payload via stdin. `SessionStart` registers the
session and `Stop` records its terminal state and `last_assistant_message`.

#### Codex notify (optional)

For the same exceptional cases, Codex
[`notify`](https://developers.openai.com/codex/config-advanced#notifications) can
send turn-complete updates to ceeker. Add this user-level setting to
`~/.codex/config.toml`:

```toml
notify = ["ceeker", "hook", "codex"]
```

Codex appends one JSON payload argument for each supported notification (currently
`agent-turn-complete`). This is optional and is not needed for `last-message`.

[Codex lifecycle hooks](https://developers.openai.com/codex/hooks) are not
recommended for everyday ceeker use. Codex command hooks run synchronously;
handlers marked `async` are currently skipped. Since the session-file watcher
already supplies the normal monitoring path, do not enable Codex hooks only for
ceeker. Existing `ceeker hook codex ...` configurations remain accepted for
backward compatibility; if both hooks and `notify` are enabled, ceeker receives
redundant updates.

#### pi

pi needs no ceeker-specific settings. `ceeker hook pi <event>` remains accepted
for compatibility with custom extensions, but automatic monitoring uses the pi
session file.

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
