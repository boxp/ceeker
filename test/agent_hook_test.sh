#!/usr/bin/env sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
HOOK_SCRIPT="${REPO_ROOT}/scripts/agent-hooks/lint_format_check_hook.clj"
TEST_TMP_ROOT=$(mktemp -d)
PASS_COUNT=0
FAIL_COUNT=0

cleanup() {
  rm -rf "${TEST_TMP_ROOT}"
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS %s\n' "$1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf 'FAIL %s: %s\n' "$1" "$2" >&2
}

assert_contains() {
  file_path="$1"
  expected="$2"
  label="$3"
  if ! grep -F "$expected" "$file_path" >/dev/null 2>&1; then
    fail "$label" "expected '$expected' in $file_path"
    return 1
  fi
  return 0
}

assert_empty() {
  file_path="$1"
  label="$2"
  if [ -s "$file_path" ]; then
    fail "$label" "expected empty file: $file_path"
    return 1
  fi
  return 0
}

assert_line_count() {
  file_path="$1"
  expected="$2"
  label="$3"
  actual=$(wc -l <"$file_path" | tr -d ' ')
  if [ "$actual" != "$expected" ]; then
    fail "$label" "expected $expected lines in $file_path, got $actual"
    return 1
  fi
  return 0
}

setup_case() {
  CASE_DIR="${TEST_TMP_ROOT}/$1"
  STUB_DIR="${CASE_DIR}/bin"
  LOG_FILE="${CASE_DIR}/make.log"
  OUTPUT_FILE="${CASE_DIR}/stdout.json"
  INPUT_FILE="${CASE_DIR}/input.json"
  MAKE_BEHAVIOR="${CASE_DIR}/make-behavior"

  mkdir -p "${STUB_DIR}"
  : >"${LOG_FILE}"
  : >"${OUTPUT_FILE}"
  : >"${MAKE_BEHAVIOR}"

  cat >"${STUB_DIR}/make" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" >> "${HOOK_TEST_MAKE_LOG}"

if [ -f "${HOOK_TEST_MAKE_BEHAVIOR}" ]; then
  while IFS='=' read -r key value; do
    [ -n "${key}" ] || continue
    if [ "$1" = "${key}" ]; then
      if [ "${value}" = "0" ]; then
        printf '%s ok\n' "$1"
        exit 0
      fi
      printf '%s failed\n' "$1" >&2
      exit "${value}"
    fi
  done < "${HOOK_TEST_MAKE_BEHAVIOR}"
fi

printf '%s ok\n' "$1"
EOF
  chmod +x "${STUB_DIR}/make"
}

run_hook() {
  payload="$1"
  printf '%s\n' "$payload" >"${INPUT_FILE}"
  HOOK_TEST_MAKE_LOG="${LOG_FILE}" \
  HOOK_TEST_MAKE_BEHAVIOR="${MAKE_BEHAVIOR}" \
  PATH="${STUB_DIR}:${PATH}" \
  bb "${HOOK_SCRIPT}" <"${INPUT_FILE}" >"${OUTPUT_FILE}"
}

test_write_runs_format_and_lint() {
  setup_case "write-runs-checks"

  if run_hook '{"hook_event_name":"PostToolUse","tool_name":"Write","tool_input":{"file_path":"src/ceeker/core.clj"}}'; then
    assert_line_count "${LOG_FILE}" 2 "write runs both make targets" || return 1
    assert_contains "${LOG_FILE}" "format-check" "write runs format-check first" || return 1
    assert_contains "${LOG_FILE}" "lint" "write runs lint second" || return 1
    assert_contains "${OUTPUT_FILE}" "format-check and lint passed after Write" "write pass message" || return 1
    pass "Write tool triggers format-check and lint"
    return 0
  fi

  fail "Write tool triggers format-check and lint" "hook command failed"
  return 1
}

test_read_only_bash_skips_checks() {
  setup_case "bash-skip"

  if run_hook '{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"rg hook README.md"}}'; then
    assert_empty "${LOG_FILE}" "read-only bash should skip make" || return 1
    assert_empty "${OUTPUT_FILE}" "read-only bash should not emit message" || return 1
    pass "read-only Bash command skips checks"
    return 0
  fi

  fail "read-only Bash command skips checks" "hook command failed"
  return 1
}

test_mutating_bash_runs_checks() {
  setup_case "bash-runs-checks"

  if run_hook '{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"git apply /tmp/change.patch"}}'; then
    assert_line_count "${LOG_FILE}" 2 "mutating bash runs both make targets" || return 1
    assert_contains "${OUTPUT_FILE}" "format-check and lint passed after Bash command" "bash pass message" || return 1
    pass "mutating Bash command triggers checks"
    return 0
  fi

  fail "mutating Bash command triggers checks" "hook command failed"
  return 1
}

test_format_check_failure_reports_message() {
  setup_case "format-failure"
  printf 'format-check=1\n' >"${MAKE_BEHAVIOR}"

  if run_hook '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"src/ceeker/core.clj"}}'; then
    assert_line_count "${LOG_FILE}" 1 "format-check failure should stop before lint" || return 1
    assert_contains "${OUTPUT_FILE}" "format-check failed after Edit" "format failure message" || return 1
    assert_contains "${OUTPUT_FILE}" "format-check failed" "format failure output" || return 1
    pass "format-check failure is reported"
    return 0
  fi

  fail "format-check failure is reported" "hook command failed"
  return 1
}

test_write_runs_format_and_lint
test_read_only_bash_skips_checks
test_mutating_bash_runs_checks
test_format_check_failure_reports_message

if [ "${FAIL_COUNT}" -ne 0 ]; then
  printf '%s test(s) failed\n' "${FAIL_COUNT}" >&2
  exit 1
fi

printf '%s test(s) passed\n' "${PASS_COUNT}"
