#!/usr/bin/env sh
set -eu

REPO_OWNER="boxp"
REPO_NAME="ceeker"
DEFAULT_BIN_DIR="${HOME}/.local/bin"

usage() {
  cat <<'EOF'
Usage: install.sh [-b BIN_DIR] [-v VERSION]

Options:
  -b, --bin-dir   Installation directory (default: ~/.local/bin)
  -v, --version   Release version without leading v (default: latest)
  -h, --help      Show this help
EOF
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

normalize_version() {
  case "$1" in
    "" | latest)
      printf 'latest\n'
      ;;
    v*)
      printf '%s\n' "${1#v}"
      ;;
    *)
      printf '%s\n' "$1"
      ;;
  esac
}

detect_platform() {
  os_name=$(uname -s)
  arch_name=$(uname -m)

  case "${os_name}" in
    Linux)
      case "${arch_name}" in
        x86_64 | amd64)
          printf 'linux-amd64\n'
          return 0
          ;;
        aarch64 | arm64)
          printf 'linux-arm64\n'
          return 0
          ;;
      esac
      ;;
    Darwin)
      case "${arch_name}" in
        arm64 | aarch64)
          printf 'darwin-arm64\n'
          return 0
          ;;
      esac
      ;;
  esac

  fail "Unsupported platform: ${os_name} ${arch_name}"
}

download_url() {
  platform="$1"
  version="$2"

  if [ "${version}" = "latest" ]; then
    printf 'https://github.com/%s/%s/releases/latest/download/ceeker-%s.tar.gz\n' "${REPO_OWNER}" "${REPO_NAME}" "${platform}"
    return 0
  fi

  printf 'https://github.com/%s/%s/releases/download/v%s/ceeker-%s.tar.gz\n' "${REPO_OWNER}" "${REPO_NAME}" "${version}" "${platform}"
}

checksum_url() {
  version="$1"

  if [ "${version}" = "latest" ]; then
    printf 'https://github.com/%s/%s/releases/latest/download/checksums.txt\n' "${REPO_OWNER}" "${REPO_NAME}"
    return 0
  fi

  printf 'https://github.com/%s/%s/releases/download/v%s/checksums.txt\n' "${REPO_OWNER}" "${REPO_NAME}" "${version}"
}

hash_file() {
  file_path="$1"

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file_path}" | awk '{print $1}'
    return 0
  fi

  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file_path}" | awk '{print $1}'
    return 0
  fi

  fail "Required command not found: sha256sum or shasum"
}

expected_checksum() {
  checksums_file="$1"
  tarball_name="$2"
  checksum=$(awk -v target="${tarball_name}" '$2 == target { print $1 }' "${checksums_file}")
  [ -n "${checksum}" ] || fail "Checksum entry not found for ${tarball_name}"
  printf '%s\n' "${checksum}"
}

bin_dir="${BIN_DIR:-${DEFAULT_BIN_DIR}}"
version="$(normalize_version "${VERSION:-latest}")"

while [ "$#" -gt 0 ]; do
  case "$1" in
    -b | --bin-dir)
      [ "$#" -ge 2 ] || fail "Missing value for $1"
      bin_dir="$2"
      shift 2
      ;;
    -v | --version)
      [ "$#" -ge 2 ] || fail "Missing value for $1"
      version="$(normalize_version "$2")"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

require_command curl
require_command tar
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  fail "Required command not found: sha256sum or shasum"
fi

platform="$(detect_platform)"
tarball_name="ceeker-${platform}.tar.gz"
tmp_dir="$(mktemp -d)"
tarball_path="${tmp_dir}/${tarball_name}"
checksums_path="${tmp_dir}/checksums.txt"
binary_path="${tmp_dir}/ceeker-${platform}"
install_path="${bin_dir}/ceeker"

cleanup() {
  rm -rf "${tmp_dir}"
}

trap cleanup EXIT HUP INT TERM

curl -fsSL "$(download_url "${platform}" "${version}")" -o "${tarball_path}"
curl -fsSL "$(checksum_url "${version}")" -o "${checksums_path}"

expected="$(expected_checksum "${checksums_path}" "${tarball_name}")"
actual="$(hash_file "${tarball_path}")"
[ "${expected}" = "${actual}" ] || fail "Checksum mismatch for ${tarball_name}"

mkdir -p "${bin_dir}"
tar -xzf "${tarball_path}" -C "${tmp_dir}"
[ -f "${binary_path}" ] || fail "Extracted binary not found: ceeker-${platform}"
chmod +x "${binary_path}"
mv "${binary_path}" "${install_path}"

"${install_path}" --version >/dev/null 2>&1 || fail "Installed binary failed validation: ${install_path}"

printf 'Installed ceeker to %s\n' "${install_path}"
case ":${PATH:-}:" in
  *:"${bin_dir}":*)
    ;;
  *)
    printf 'Add %s to PATH\n' "${bin_dir}"
    ;;
esac
