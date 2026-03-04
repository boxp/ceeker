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

# Timeouts (seconds) - native-image binaries may have slow first-run
# due to class initialization and subprocess spawning in GraalVM.
TUI_READY_TIMEOUT=90
TUI_EXIT_TIMEOUT=30
SEARCH_POLL_TIMEOUT=15

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
  # Check if binary process is still running
  tmux list-panes -t ceeker-e2e -F '#{pane_pid} #{pane_dead}' > "$test_dir/tmux-pane-info.txt" 2>/dev/null || true
  cp -r "${TMPDIR_E2E}/state/" "$test_dir/state/" 2>/dev/null || true
  cp -r "${TMPDIR_E2E}/ceeker/" "$test_dir/ceeker-state/" 2>/dev/null || true
  cp "${TMPDIR_E2E}/"*.stderr "$test_dir/" 2>/dev/null || true
  echo "--- Diagnostics for $test_name ---"
  echo "tmux pane capture:"
  cat "$test_dir/tmux-capture.txt" 2>/dev/null || echo "(empty)"
  echo "stderr:"
  cat "$test_dir/"*.stderr 2>/dev/null || echo "(empty)"
  echo "---"
}

# Wait for TUI to render by polling tmux capture-pane for the "ceeker" header.
wait_for_tui_ready() {
  local waited=0
  while [ "$waited" -lt "$TUI_READY_TIMEOUT" ]; do
    # Check if tmux session is still alive
    if ! tmux has-session -t ceeker-e2e 2>/dev/null; then
      echo "  [diag] tmux session died at ${waited}s"
      return 1
    fi
    if tmux capture-pane -t ceeker-e2e -p 2>/dev/null | grep -q "ceeker"; then
      echo "  [diag] TUI ready after ${waited}s"
      return 0
    fi
    # Log progress every 15 seconds
    if [ "$((waited % 15))" -eq 0 ] && [ "$waited" -gt 0 ]; then
      echo "  [diag] still waiting for TUI... (${waited}s)"
      tmux capture-pane -t ceeker-e2e -p 2>/dev/null | head -3 || true
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "  [diag] TUI not ready after ${TUI_READY_TIMEOUT}s"
  return 1
}

# Wait for exit marker file to appear
wait_for_exit() {
  local marker="$1"
  local waited=0
  while [ ! -f "$marker" ] && [ "$waited" -lt "$TUI_EXIT_TIMEOUT" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  [ -f "$marker" ]
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

  local start_time
  start_time="$(date +%s)"
  if echo "$payload" | XDG_RUNTIME_DIR="$TMPDIR_E2E" "$BINARY" hook claude SessionStart 2>"$stderr_file"; then
    local end_time
    end_time="$(date +%s)"
    echo "  [diag] hook claude took $((end_time - start_time))s"
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

  local start_time
  start_time="$(date +%s)"
  if XDG_RUNTIME_DIR="$TMPDIR_E2E" "$BINARY" hook codex "$payload" 2>"$stderr_file"; then
    local end_time
    end_time="$(date +%s)"
    echo "  [diag] hook codex took $((end_time - start_time))s"
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

  if ! wait_for_tui_ready; then
    # TUI didn't start - check if process exited
    if [ -f "$exit_marker" ]; then
      local code
      code="$(cat "$exit_marker")"
      fail "TUI quit" "binary exited early with code $code"
    else
      fail "TUI quit" "TUI did not render within ${TUI_READY_TIMEOUT}s"
    fi
    collect_diagnostics "tui-quit"
    tmux kill-session -t ceeker-e2e 2>/dev/null || true
    return
  fi

  tmux send-keys -t ceeker-e2e q

  if wait_for_exit "$exit_marker"; then
    local code
    code="$(cat "$exit_marker")"
    if [ "$code" = "0" ]; then
      pass "TUI starts and quits cleanly with 'q'"
    else
      fail "TUI quit" "exit code $code"
      collect_diagnostics "tui-quit"
    fi
  else
    fail "TUI quit" "timed out waiting for exit after ${TUI_EXIT_TIMEOUT}s"
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

  if ! wait_for_tui_ready; then
    if [ -f "$exit_marker" ]; then
      local code
      code="$(cat "$exit_marker")"
      fail "TUI search" "binary exited early with code $code"
    else
      fail "TUI search" "TUI did not render within ${TUI_READY_TIMEOUT}s"
    fi
    collect_diagnostics "tui-search"
    tmux kill-session -t ceeker-e2e 2>/dev/null || true
    return
  fi

  # Enter search mode
  tmux send-keys -t ceeker-e2e /
  # Wait for search prompt to appear
  local sw=0
  while [ "$sw" -lt "$SEARCH_POLL_TIMEOUT" ]; do
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
  while [ "$ew" -lt "$SEARCH_POLL_TIMEOUT" ]; do
    if ! tmux capture-pane -t ceeker-e2e -p 2>/dev/null | grep -q "Search:"; then
      break
    fi
    sleep 1
    ew=$((ew + 1))
  done

  # Quit
  tmux send-keys -t ceeker-e2e q

  if wait_for_exit "$exit_marker"; then
    local code
    code="$(cat "$exit_marker")"
    if [ "$code" = "0" ]; then
      pass "TUI search mode works: / -> Esc -> q"
    else
      fail "TUI search" "exit code $code"
      collect_diagnostics "tui-search"
    fi
  else
    fail "TUI search" "timed out waiting for exit after ${TUI_EXIT_TIMEOUT}s"
    collect_diagnostics "tui-search"
    tmux kill-session -t ceeker-e2e 2>/dev/null || true
  fi
}

# --- Run all tests ---
log "Running ceeker native-image E2E tests"
echo "  Binary: $BINARY"
echo "  Temp dir: $TMPDIR_E2E"
echo "  TUI ready timeout: ${TUI_READY_TIMEOUT}s"
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
