# ceeker

> [日本語版 (Japanese)](./README.ja.md)

A TUI for monitoring AI coding agent sessions and progress.

In environments where multiple AI coding agents (Claude Code / Codex) run in parallel, ceeker provides a unified view of all sessions with the ability to jump to individual tmux panes.

## Prerequisites

- tmux

## Installation

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
- **Session filtering**: Filter the display by agent type, status, or text search

**Key bindings:**

| Key | Action |
|-----|--------|
| `j` / `↓` | Move down |
| `k` / `↑` | Move up |
| `Enter` | Jump to the selected session's tmux pane |
| `r` | Manual refresh |
| `v` | Toggle view mode (Auto → Table → Card) |
| `a` | Toggle agent type filter (All → Claude → Codex → All) |
| `s` | Toggle status filter (All → running → completed → error → waiting → idle → All) |
| `/` | Text search (partial match on session-id / cwd) |
| `c` | Clear all filters |
| `q` | Quit |

### Hook CLI

```bash
# Process Claude Code hook events (receives JSON payload via stdin)
ceeker hook claude Notification <<< '{"session_id":"abc","cwd":"/tmp","hook_event_name":"Notification","title":"Working..."}'

# Process Codex notify hook events (Codex passes JSON as the last argument)
ceeker hook codex '{"type":"agent-turn-complete","thread-id":"xyz","cwd":"/tmp","last-assistant-message":"Done."}'
# Legacy format is also supported
ceeker hook codex notification '{"session_id":"xyz","message":"Testing..."}'
```

## Hook Configuration

### Claude Code

Add the following to `.claude/settings.json` (using the 3-level nesting format per the [official spec](https://docs.anthropic.com/en/docs/claude-code/hooks)):

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude SessionStart" }] }
    ],
    "Notification": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude Notification" }] }
    ],
    "PreToolUse": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude PreToolUse" }] }
    ],
    "PostToolUse": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude PostToolUse" }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude Stop" }] }
    ]
  }
}
```

Claude Code passes a JSON payload to hook commands via stdin. The payload contains common fields such as `session_id`, `cwd`, and `hook_event_name` (see the [Hooks reference](https://docs.anthropic.com/en/docs/claude-code/hooks)).

### Codex

Add the following to `~/.codex/config.toml`:

```toml
notify = ["ceeker", "hook", "codex"]
```

Codex appends the JSON payload as the last argument of the `notify` command (via argv, not stdin).

## Async Hook Execution

Hook CLI commands (`ceeker hook ...`) run IO operations (state persistence and stale session cleanup) asynchronously in a background thread. This prevents slow file locks or tmux queries from blocking the calling agent process.

**Behavior:**

- Payload parsing and normalization happen synchronously (fast)
- State persistence (`sessions.edn` write) and stale session cleanup run in background
- Background operations have a **5-second timeout** — if exceeded, ceeker exits without waiting
- Background errors are logged to stderr but **never propagated** to the calling agent
- The agent process is never blocked by hook failures or delays

## Automatic Session Cleanup

When a tmux pane is closed, the corresponding session automatically transitions to the `Closed` state.

**Check timing:**

- All sessions are checked at TUI startup
- Periodic checks run approximately every 10 seconds while the TUI is displayed
- Checks also run when a hook event is received

**How it works:**

A single `tmux list-panes -a` call retrieves the cwd and PID of all panes, which are then matched against sessions in the `running` state. A session transitions to `closed` under the following conditions:

1. **Pane not found**: No tmux pane exists matching the session's cwd
2. **Process tree search**: Even if a pane with a matching cwd exists, the target agent (claude/codex) process is not found in the pane's process tree

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

## CI

GitHub Actions runs the following jobs on PRs and pushes to main:

- **lint**: clj-kondo lint + cljfmt format-check
- **test**: Clojure unit tests
- **native-e2e**: GraalVM native-image build + E2E tests

### native-e2e

Runs E2E tests against a binary built with native-image to catch native-image-specific issues that don't reproduce on the JVM.

Test cases:
- `--help` output verification
- Hook commands (Claude / Codex) session recording
- TUI startup and exit (`q` key)
- TUI search mode (`/` → `Esc` → `q`)

TUI tests use tmux to simulate a terminal.

## License

MIT
