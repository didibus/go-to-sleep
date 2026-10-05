#!/bin/bash

packaging_die() {
  echo "Go To Sleep packaging: $*" >&2
  exit 2
}

canonical_path() {
  /usr/bin/python3 -c \
    'import os, sys; print(os.path.realpath(sys.argv[1]))' "$1"
}

validate_release_version() {
  local version="$1"
  if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    packaging_die "invalid release version: ${version:-<empty>}"
  fi
}

query_release_version() {
  local output="$1"
  local byte_count
  local version

  command -v jolt >/dev/null 2>&1 || packaging_die "jolt is required"
  jolt -e "(require '[gotosleep.version :as v]) (print v/value)" > "$output"
  byte_count="$(LC_ALL=C /usr/bin/wc -c < "$output" | /usr/bin/tr -d ' ')"
  version="$(/bin/cat "$output")"
  validate_release_version "$version"
  if [ "$byte_count" != "${#version}" ]; then
    packaging_die "release version output contains surrounding or non-text bytes"
  fi
  RELEASE_VERSION="$version"
}

require_signable_toolchain() {
  local chez_dir
  local expected_chez
  local resolved_chez
  local clang_path
  local jolt_version

  jolt_version="$(jolt --version 2>&1)" ||
    packaging_die "could not determine the Jolt version"
  [ "$jolt_version" = "jolt v0.8.16" ] ||
    packaging_die "Jolt 0.8.16 is required (found: $jolt_version)"

  [ -n "${JOLT_CHEZ_CSV-}" ] ||
    packaging_die "JOLT_CHEZ_CSV must name the Chez 10.4.1 tarm64osx development directory"
  [ -d "$JOLT_CHEZ_CSV" ] ||
    packaging_die "JOLT_CHEZ_CSV is not a directory: $JOLT_CHEZ_CSV"
  chez_dir="$(canonical_path "$JOLT_CHEZ_CSV")"
  expected_chez="$chez_dir/chez"

  [ -x "$expected_chez" ] ||
    packaging_die "JOLT_CHEZ_CSV/chez is missing or not executable"
  for required_file in scheme.h petite.boot scheme.boot; do
    [ -f "$chez_dir/$required_file" ] ||
      packaging_die "JOLT_CHEZ_CSV/$required_file is missing"
  done
  [ "$("$expected_chez" --version 2>&1)" = "10.4.1" ] ||
    packaging_die "Chez Scheme 10.4.1 is required"

  resolved_chez="$(command -v chez 2>/dev/null || true)"
  [ -n "$resolved_chez" ] ||
    packaging_die "chez must resolve through PATH"
  [ "$(canonical_path "$resolved_chez")" = "$expected_chez" ] ||
    packaging_die "PATH must resolve chez from JOLT_CHEZ_CSV"

  clang_path="$(command -v clang 2>/dev/null || true)"
  [ -n "$clang_path" ] || packaging_die "Apple Clang is required"
  "$clang_path" --version 2>/dev/null | /usr/bin/grep -q '^Apple clang version ' ||
    packaging_die "clang must be Apple Clang"

  for tool in \
    /usr/bin/codesign \
    /usr/bin/lipo \
    /usr/bin/plutil \
    /usr/bin/xattr; do
    [ -x "$tool" ] || packaging_die "required tool is unavailable: $tool"
  done
}

verify_adhoc_signature() {
  local path="$1"
  local identifier="$2"
  local details

  /usr/bin/codesign --verify --strict --verbose=2 "$path"
  details="$(/usr/bin/codesign --display --verbose=4 "$path" 2>&1)"
  printf '%s\n' "$details" | /usr/bin/grep -Fqx "Identifier=$identifier" ||
    packaging_die "unexpected signing identifier for $path"
  printf '%s\n' "$details" | /usr/bin/grep -Fqx "Signature=adhoc" ||
    packaging_die "signature is not ad-hoc for $path"
  printf '%s\n' "$details" | /usr/bin/grep -Fqx "TeamIdentifier=not set" ||
    packaging_die "unexpected Team ID for $path"
  if printf '%s\n' "$details" | /usr/bin/grep -q '^Authority='; then
    packaging_die "certificate authority found on ad-hoc signature for $path"
  fi
}
