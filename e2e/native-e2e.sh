#!/usr/bin/env bash
# E2E tests for ceeker native-image binary.
# Validates that the native binary behaves correctly
# (catches native-image-only regressions).
set -euo pipefail

BINARY="${1:?Usage: $0 <path-to-ceeker-binary>}"
BINARY="$(cd "$(dirname "$BINARY")" && pwd)/$(basename "$BINARY")"

TMPDIR_E2E="$(mktemp -d)"
DIAG_DIR="${TMPDIR_E2E}/diagnostics"
mkdir -p "$DIAG_DIR"

PASS=0
FAIL=0
ERRORS=""

cleanup() {
  tmux kill-session -t ceeker-e2e 2>/dev/null || true
  rm -rf "$TMPDIR_E2E"
}
trap cleanup EXIT

log() { printf '\033[1m==> %s\033[0m\n' "$*"; }
pass() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() {
  FAIL=$((FAIL + 1))
  ERRORS="${ERRORS}\n  - $1: $2"
  printf '  \033[31mFAIL\033[0m %s: %s\n' "$1" "$2"
}

collect_diagnostics() {
  local test_name="$1"
  local test_dir="${DIAG_DIR}/${test_name}"
  mkdir -p "$test_dir"
  tmux capture-pane -t ceeker-e2e -p > "$test_dir/tmux-capture.txt" 2>/dev/null || true
  cp -r "${TMPDIR_E2E}/state/" "$test_dir/state/" 2>/dev/null || true
  cp "${TMPDIR_E2E}/"*.stderr "$test_dir/" 2>/dev/null || true
}

# Wait for TUI to render by polling tmux capture-pane for the "ceeker" header.
# Falls back to fixed sleep after timeout.
wait_for_tui_ready() {
  local waited=0
  local max_wait=15
  while [ "$waited" -lt "$max_wait" ]; do
    if tmux capture-pane -t ceeker-e2e -p 2>/dev/null | grep -q "ceeker"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  # TUI may still be usable even if header not detected
  return 0
}

# Require tmux to be installed
if ! command -v tmux >/dev/null 2>&1; then
  echo "ERROR: tmux is required for TUI E2E tests but not found" >&2
  exit 1
fi

# --- Test 1: --help ---
test_help() {
  log "Test: --help"
  local out stderr_file="${TMPDIR_E2E}/help.stderr"
  if out="$("$BINARY" --help 2>"$stderr_file")"; then
    if echo "$out" | grep -q "ceeker"; then
      pass "--help prints usage and exits 0"
    else
      fail "--help" "output does not contain 'ceeker'"
      collect_diagnostics "help"
    fi
  else
    fail "--help" "exit code $?"
    collect_diagnostics "help"
  fi
}

# --- Test 2: hook claude SessionStart ---
test_hook_claude() {
  log "Test: hook claude SessionStart"
  local state_dir="${TMPDIR_E2E}/state"
  mkdir -p "$state_dir"
  local stderr_file="${TMPDIR_E2E}/hook-claude.stderr"

  local payload='{"session_id":"e2e-test-001","cwd":"/tmp/e2e","hook_event_name":"SessionStart"}'

  if echo "$payload" | XDG_RUNTIME_DIR="$TMPDIR_E2E" "$BINARY" hook claude SessionStart 2>"$stderr_file"; then
    local sessions_file
    sessions_file="$(find "$TMPDIR_E2E" -name 'sessions.edn' -type f 2>/dev/null | head -1)"
    if [ -n "$sessions_file" ] && grep -q "e2e-test-001" "$sessions_file"; then
      pass "hook claude SessionStart records session"
    else
      fail "hook claude" "sessions.edn not found or missing session id"
      collect_diagnostics "hook-claude"
    fi
  else
    fail "hook claude" "exit code $?"
    collect_diagnostics "hook-claude"
  fi
}

# --- Test 3: hook codex (JSON argv) ---
test_hook_codex() {
  log "Test: hook codex JSON argv"
  local stderr_file="${TMPDIR_E2E}/hook-codex.stderr"

  local payload='{"type":"agent-turn-complete","thread-id":"e2e-codex-001","cwd":"/tmp/e2e-codex","last-assistant-message":"Done testing."}'

  if XDG_RUNTIME_DIR="$TMPDIR_E2E" "$BINARY" hook codex "$payload" 2>"$stderr_file"; then
    local sessions_file
    sessions_file="$(find "$TMPDIR_E2E" -name 'sessions.edn' -type f 2>/dev/null | head -1)"
    if [ -n "$sessions_file" ] && grep -q "e2e-codex-001" "$sessions_file"; then
      pass "hook codex records session"
    else
      fail "hook codex" "sessions.edn missing codex session"
      collect_diagnostics "hook-codex"
    fi
  else
    fail "hook codex" "exit code $?"
    collect_diagnostics "hook-codex"
  fi
}

# --- Test 4: TUI start + quit ---
test_tui_start_quit() {
  log "Test: TUI start and quit with 'q'"
  local state_dir="${TMPDIR_E2E}/state-tui"
  mkdir -p "$state_dir"
  local stderr_file="${TMPDIR_E2E}/tui-quit.stderr"
  local exit_marker="${TMPDIR_E2E}/tui-quit.exit"

  tmux kill-session -t ceeker-e2e 2>/dev/null || true

  tmux new-session -d -s ceeker-e2e -x 120 -y 40 \
    "XDG_RUNTIME_DIR='${TMPDIR_E2E}' '${BINARY}' 2>'${stderr_file}'; echo \$? > '${exit_marker}'"

  wait_for_tui_ready

  tmux send-keys -t ceeker-e2e q

  local waited=0
  while [ ! -f "$exit_marker" ] && [ "$waited" -lt 15 ]; do
    sleep 1
    waited=$((waited + 1))
  done

  if [ -f "$exit_marker" ]; then
    local code
    code="$(cat "$exit_marker")"
    if [ "$code" = "0" ]; then
      pass "TUI starts and quits cleanly with 'q'"
    else
      fail "TUI quit" "exit code $code"
      collect_diagnostics "tui-quit"
    fi
  else
    fail "TUI quit" "timed out waiting for exit"
    collect_diagnostics "tui-quit"
    tmux kill-session -t ceeker-e2e 2>/dev/null || true
  fi
}

# --- Test 5: TUI search mode (/, Esc, q) ---
test_tui_search_escape() {
  log "Test: TUI search mode: / -> Esc -> q"
  local stderr_file="${TMPDIR_E2E}/tui-search.stderr"
  local exit_marker="${TMPDIR_E2E}/tui-search.exit"

  tmux kill-session -t ceeker-e2e 2>/dev/null || true

  tmux new-session -d -s ceeker-e2e -x 120 -y 40 \
    "XDG_RUNTIME_DIR='${TMPDIR_E2E}' '${BINARY}' 2>'${stderr_file}'; echo \$? > '${exit_marker}'"

  wait_for_tui_ready

  # Enter search mode
  tmux send-keys -t ceeker-e2e /
  # Wait for search prompt to appear
  local sw=0
  while [ "$sw" -lt 10 ]; do
    if tmux capture-pane -t ceeker-e2e -p 2>/dev/null | grep -q "Search:"; then
      break
    fi
    sleep 1
    sw=$((sw + 1))
  done

  # Press Escape to exit search
  tmux send-keys -t ceeker-e2e Escape
  # Wait for search prompt to disappear
  local ew=0
  while [ "$ew" -lt 10 ]; do
    if ! tmux capture-pane -t ceeker-e2e -p 2>/dev/null | grep -q "Search:"; then
      break
    fi
    sleep 1
    ew=$((ew + 1))
  done

  # Quit
  tmux send-keys -t ceeker-e2e q

  local waited=0
  while [ ! -f "$exit_marker" ] && [ "$waited" -lt 15 ]; do
    sleep 1
    waited=$((waited + 1))
  done

  if [ -f "$exit_marker" ]; then
    local code
    code="$(cat "$exit_marker")"
    if [ "$code" = "0" ]; then
      pass "TUI search mode works: / -> Esc -> q"
    else
      fail "TUI search" "exit code $code"
      collect_diagnostics "tui-search"
    fi
  else
    fail "TUI search" "timed out waiting for exit"
    collect_diagnostics "tui-search"
    tmux kill-session -t ceeker-e2e 2>/dev/null || true
  fi
}

# --- Run all tests ---
log "Running ceeker native-image E2E tests"
echo "  Binary: $BINARY"
echo "  Temp dir: $TMPDIR_E2E"
echo ""

test_help
test_hook_claude
test_hook_codex
test_tui_start_quit
test_tui_search_escape

echo ""
log "Results: $PASS passed, $FAIL failed"

if [ "$FAIL" -gt 0 ]; then
  printf '\033[31mFailed tests:%b\033[0m\n' "$ERRORS"
  if [ -n "${GITHUB_WORKSPACE:-}" ]; then
    cp -r "$DIAG_DIR" "${GITHUB_WORKSPACE}/e2e-diagnostics" 2>/dev/null || true
  fi
  exit 1
fi
