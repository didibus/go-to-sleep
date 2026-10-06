#!/bin/bash
# Focused regression checks for signable-build toolchain discovery.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
COMMON="$ROOT/packaging/common.sh"
TMP="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/gotosleep-toolchain.XXXXXX")"

cleanup() {
  /bin/rm -rf -- "$TMP"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "packaging toolchain test: $*" >&2
  exit 1
}

make_chez() {
  local path="$1"
  local version="$2"
  /bin/mkdir -p "$(dirname "$path")"
  /usr/bin/printf '#!/bin/sh\nprintf "%%s\\n" "%s"\n' "$version" >"$path"
  /bin/chmod 0755 "$path"
}

make_csv() {
  local path="$1"
  local version="$2"
  /bin/mkdir -p "$path"
  make_chez "$path/chez" "$version"
  : >"$path/scheme.h"
  : >"$path/libkernel.a"
  : >"$path/petite.boot"
  : >"$path/scheme.boot"
}

TOOLS="$TMP/tools"
/bin/mkdir -p "$TOOLS"
/usr/bin/printf '#!/bin/sh\nprintf "jolt v0.8.16\\n"\n' >"$TOOLS/jolt"
/usr/bin/printf '#!/bin/sh\nprintf "Apple clang version 18.0.0\\n"\n' >"$TOOLS/clang"
/bin/chmod 0755 "$TOOLS/jolt" "$TOOLS/clang"
BASE_PATH="$TOOLS:/usr/bin:/bin:/usr/sbin:/sbin"

NORMAL_ROOT="$TMP/normal"
NORMAL_CSV="$NORMAL_ROOT/lib/csv10.4.1/tarm64osx"
make_chez "$NORMAL_ROOT/bin/chez" "10.4.1"
make_csv "$NORMAL_CSV" "10.4.1"
NORMAL_CSV_CANON="$(
  /usr/bin/python3 -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$NORMAL_CSV"
)"

normal="$(
  /usr/bin/env -u JOLT_CHEZ -u JOLT_CHEZ_CSV \
    PATH="$NORMAL_ROOT/bin:$BASE_PATH" \
    /bin/bash -c '
      source "$1"
      require_signable_toolchain
      printf "%s\n%s\n" "$JOLT_CHEZ_CSV" "$JOLT_CHEZ"
    ' bash "$COMMON"
)" || fail "PATH discovery failed"
[ "$normal" = "$NORMAL_CSV_CANON
$NORMAL_CSV_CANON/chez" ] || fail "PATH discovery selected unexpected paths"

override="$(
  /usr/bin/env -u JOLT_CHEZ \
    JOLT_CHEZ_CSV="$NORMAL_CSV" \
    PATH="$BASE_PATH" \
    /bin/bash -c '
      source "$1"
      require_signable_toolchain
      printf "%s\n%s\n" "$JOLT_CHEZ_CSV" "$JOLT_CHEZ"
    ' bash "$COMMON"
)" || fail "JOLT_CHEZ_CSV override failed"
[ "$override" = "$NORMAL_CSV_CANON
$NORMAL_CSV_CANON/chez" ] || fail "JOLT_CHEZ_CSV selected unexpected paths"

explicit="$(
  /usr/bin/env -u JOLT_CHEZ_CSV \
    JOLT_CHEZ="$NORMAL_ROOT/bin/chez" \
    PATH="$BASE_PATH" \
    /bin/bash -c '
      source "$1"
      require_signable_toolchain
      printf "%s\n%s\n" "$JOLT_CHEZ_CSV" "$JOLT_CHEZ"
    ' bash "$COMMON"
)" || fail "JOLT_CHEZ discovery failed"
[ "$explicit" = "$NORMAL_CSV_CANON
$NORMAL_CSV_CANON/chez" ] || fail "JOLT_CHEZ selected unexpected paths"

set +e
/usr/bin/env -u JOLT_CHEZ -u JOLT_CHEZ_CSV \
  PATH="$BASE_PATH" \
  /bin/bash -c 'source "$1"; require_signable_toolchain' bash "$COMMON" \
  >"$TMP/missing.out" 2>&1
missing_status=$?
set -e
[ "$missing_status" -eq 2 ] || fail "missing Chez exited $missing_status instead of 2"
/usr/bin/grep -Fq 'install Chez Scheme 10.4.1 or set JOLT_CHEZ_CSV' "$TMP/missing.out" ||
  fail "missing Chez error was not actionable"

INCOMPLETE="$TMP/incomplete"
make_chez "$INCOMPLETE/chez" "10.4.1"
: >"$INCOMPLETE/scheme.h"
: >"$INCOMPLETE/petite.boot"
: >"$INCOMPLETE/scheme.boot"
set +e
/usr/bin/env -u JOLT_CHEZ \
  JOLT_CHEZ_CSV="$INCOMPLETE" \
  PATH="$BASE_PATH" \
  /bin/bash -c 'source "$1"; require_signable_toolchain' bash "$COMMON" \
  >"$TMP/incomplete.out" 2>&1
incomplete_status=$?
set -e
[ "$incomplete_status" -eq 2 ] ||
  fail "incomplete development directory exited $incomplete_status instead of 2"
/usr/bin/grep -Fq 'libkernel.a' "$TMP/incomplete.out" ||
  fail "incomplete development directory did not name the missing file"

WRONG="$TMP/wrong-version"
make_csv "$WRONG" "10.4.0"
set +e
/usr/bin/env -u JOLT_CHEZ \
  JOLT_CHEZ_CSV="$WRONG" \
  PATH="$BASE_PATH" \
  /bin/bash -c 'source "$1"; require_signable_toolchain' bash "$COMMON" \
  >"$TMP/wrong-version.out" 2>&1
wrong_status=$?
set -e
[ "$wrong_status" -eq 2 ] || fail "wrong Chez version exited $wrong_status instead of 2"
/usr/bin/grep -Fq 'Chez Scheme 10.4.1 is required' "$TMP/wrong-version.out" ||
  fail "wrong-version error did not explain the requirement"

echo "packaging_toolchain.sh: ok"
