#!/usr/bin/env sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
INSTALL_SH="${REPO_ROOT}/install.sh"
TEST_TMP_ROOT=$(mktemp -d)
REAL_SHA256SUM=$(command -v sha256sum || true)
REAL_SHASUM=$(command -v shasum || true)
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

assert_file_exists() {
  if [ ! -f "$1" ]; then
    fail "$2" "missing file: $1"
    return 1
  fi
  return 0
}

assert_contains() {
  if ! grep -F "$2" "$1" >/dev/null 2>&1; then
    fail "$3" "expected '$2' in $1"
    return 1
  fi
  return 0
}

assert_not_exists() {
  if [ -e "$1" ]; then
    fail "$2" "unexpected path exists: $1"
    return 1
  fi
  return 0
}

compute_sha() {
  if [ -n "${REAL_SHA256SUM}" ]; then
    "${REAL_SHA256SUM}" "$1" | awk '{print $1}'
    return 0
  fi

  if [ -n "${REAL_SHASUM}" ]; then
    "${REAL_SHASUM}" -a 256 "$1" | awk '{print $1}'
    return 0
  fi

  echo "sha256 tool not found" >&2
  exit 1
}

write_stub() {
  target="$1"
  shift
  cat >"${target}" <<EOF
$*
EOF
  chmod +x "${target}"
}

setup_case() {
  CASE_DIR="${TEST_TMP_ROOT}/$1"
  HOME_DIR="${CASE_DIR}/home"
  STUB_DIR="${CASE_DIR}/bin"
  FIXTURE_DIR="${CASE_DIR}/fixtures"
  OUTPUT_FILE="${CASE_DIR}/output.log"
  CURL_LOG="${CASE_DIR}/curl.log"

  mkdir -p "${HOME_DIR}" "${STUB_DIR}" "${FIXTURE_DIR}"
  : >"${CURL_LOG}"

  write_stub "${STUB_DIR}/curl" '#!/usr/bin/env sh
set -eu
out=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o|--output)
      out="$2"
      shift 2
      ;;
    -f|-s|-S|-L|-fsSL)
      shift
      ;;
    *)
      url="$1"
      shift
      ;;
  esac
done
[ -n "${out}" ] || { echo "curl stub requires -o" >&2; exit 1; }
[ -n "${url}" ] || { echo "curl stub requires url" >&2; exit 1; }
printf "%s\n" "${url}" >> "${CURL_LOG}"
cp "${FIXTURE_DIR}/$(basename "${url}")" "${out}"'

  write_stub "${STUB_DIR}/uname" '#!/usr/bin/env sh
set -eu
case "${1:-}" in
  -s)
    printf "%s\n" "${FAKE_UNAME_S}"
    ;;
  -m)
    printf "%s\n" "${FAKE_UNAME_M}"
    ;;
  *)
    echo "unsupported uname args" >&2
    exit 1
    ;;
esac'

  if [ -n "${REAL_SHA256SUM}" ]; then
    write_stub "${STUB_DIR}/sha256sum" "#!/usr/bin/env sh
set -eu
exec \"${REAL_SHA256SUM}\" \"\$@\""
  fi

  if [ -n "${REAL_SHASUM}" ]; then
    write_stub "${STUB_DIR}/shasum" "#!/usr/bin/env sh
set -eu
exec \"${REAL_SHASUM}\" \"\$@\""
  fi
}

prepare_release_fixture() {
  platform="$1"
  version_text="$2"
  checksum_mode="$3"
  payload_dir="${CASE_DIR}/payload"
  tarball="${FIXTURE_DIR}/ceeker-${platform}.tar.gz"
  binary="${payload_dir}/ceeker-${platform}"

  rm -rf "${payload_dir}" "${tarball}"
  mkdir -p "${payload_dir}"

  cat >"${binary}" <<EOF
#!/usr/bin/env sh
set -eu
if [ "\${1:-}" = "--version" ]; then
  printf 'ceeker %s\n' "${version_text}"
  exit 0
fi
printf 'ceeker test binary\n'
EOF
  chmod +x "${binary}"
  tar -czf "${tarball}" -C "${payload_dir}" "ceeker-${platform}"

  checksum=$(compute_sha "${tarball}")
  if [ "${checksum_mode}" = "mismatch" ]; then
    checksum="deadbeef"
  fi

  cat >"${FIXTURE_DIR}/checksums.txt" <<EOF
${checksum}  ceeker-${platform}.tar.gz
EOF
}

run_install() {
  HOME="${HOME_DIR}" \
  PATH="${STUB_DIR}:/bin:/usr/bin" \
  FIXTURE_DIR="${FIXTURE_DIR}" \
  CURL_LOG="${CURL_LOG}" \
  FAKE_UNAME_S="${FAKE_UNAME_S}" \
  FAKE_UNAME_M="${FAKE_UNAME_M}" \
  sh "${INSTALL_SH}" "$@" >"${OUTPUT_FILE}" 2>&1
}

test_installs_latest_linux_amd64() {
  setup_case "latest-linux-amd64"
  prepare_release_fixture "linux-amd64" "0.1.0" "ok"
  FAKE_UNAME_S="Linux"
  FAKE_UNAME_M="x86_64"

  if run_install; then
    assert_file_exists "${HOME_DIR}/.local/bin/ceeker" "latest install created binary" || return 1
    version_output=$("${HOME_DIR}/.local/bin/ceeker" --version)
    if [ "${version_output}" != "ceeker 0.1.0" ]; then
      fail "latest install created binary" "unexpected version: ${version_output}"
      return 1
    fi
    assert_contains "${CURL_LOG}" "https://github.com/boxp/ceeker/releases/latest/download/ceeker-linux-amd64.tar.gz" "latest install download URL" || return 1
    assert_contains "${OUTPUT_FILE}" "Add ${HOME_DIR}/.local/bin to PATH" "latest install PATH hint" || return 1
    pass "latest install on linux-amd64"
    return 0
  fi

  fail "latest install on linux-amd64" "install command failed"
  return 1
}

test_cli_overrides_env_and_uses_versioned_download() {
  setup_case "cli-overrides-env"
  prepare_release_fixture "linux-arm64" "1.2.3" "ok"
  FAKE_UNAME_S="Linux"
  FAKE_UNAME_M="aarch64"

  if BIN_DIR="${HOME_DIR}/env-bin" VERSION="9.9.9" run_install -b "${HOME_DIR}/cli-bin" -v "1.2.3"; then
    assert_file_exists "${HOME_DIR}/cli-bin/ceeker" "cli bin dir override" || return 1
    assert_not_exists "${HOME_DIR}/env-bin/ceeker" "env bin dir should not win" || return 1
    assert_contains "${CURL_LOG}" "https://github.com/boxp/ceeker/releases/download/v1.2.3/ceeker-linux-arm64.tar.gz" "explicit version URL" || return 1
    pass "CLI arguments override environment variables"
    return 0
  fi

  fail "CLI arguments override environment variables" "install command failed"
  return 1
}

test_installs_darwin_arm64() {
  setup_case "darwin-arm64"
  prepare_release_fixture "darwin-arm64" "0.1.0" "ok"
  FAKE_UNAME_S="Darwin"
  FAKE_UNAME_M="arm64"

  if run_install -b "${HOME_DIR}/darwin-bin"; then
    assert_file_exists "${HOME_DIR}/darwin-bin/ceeker" "darwin install created binary" || return 1
    assert_contains "${CURL_LOG}" "https://github.com/boxp/ceeker/releases/latest/download/ceeker-darwin-arm64.tar.gz" "darwin download URL" || return 1
    pass "darwin-arm64 install"
    return 0
  fi

  fail "darwin-arm64 install" "install command failed"
  return 1
}

test_rejects_unsupported_platform() {
  setup_case "unsupported-platform"
  FAKE_UNAME_S="Darwin"
  FAKE_UNAME_M="x86_64"

  if run_install; then
    fail "unsupported platform" "install command unexpectedly succeeded"
    return 1
  fi

  assert_contains "${OUTPUT_FILE}" "Unsupported platform: Darwin x86_64" "unsupported platform message" || return 1
  pass "unsupported platform rejected"
  return 0
}

test_rejects_checksum_mismatch() {
  setup_case "checksum-mismatch"
  prepare_release_fixture "linux-amd64" "0.1.0" "mismatch"
  FAKE_UNAME_S="Linux"
  FAKE_UNAME_M="x86_64"

  if run_install; then
    fail "checksum mismatch" "install command unexpectedly succeeded"
    return 1
  fi

  assert_contains "${OUTPUT_FILE}" "Checksum mismatch for ceeker-linux-amd64.tar.gz" "checksum mismatch message" || return 1
  assert_not_exists "${HOME_DIR}/.local/bin/ceeker" "checksum mismatch should not install binary" || return 1
  pass "checksum mismatch rejected"
  return 0
}

trap cleanup EXIT HUP INT TERM

test_installs_latest_linux_amd64 || true
test_cli_overrides_env_and_uses_versioned_download || true
test_installs_darwin_arm64 || true
test_rejects_unsupported_platform || true
test_rejects_checksum_mismatch || true

if [ "${FAIL_COUNT}" -ne 0 ]; then
  printf 'FAILURES %s\n' "${FAIL_COUNT}" >&2
  exit 1
fi

printf 'PASSED %s\n' "${PASS_COUNT}"
