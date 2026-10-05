#!/bin/bash
# Audit the release package without installing or executing its payload.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
# shellcheck source=packaging/common.sh
source "$ROOT/packaging/common.sh"
cd "$ROOT"

fail=0
TMP="$(/usr/bin/mktemp -d /tmp/gotosleep-check-pkg.XXXXXX)"
NEGATIVE_STAGE=""
cleanup() {
  /bin/rm -rf -- "$TMP"
  if [ -n "$NEGATIVE_STAGE" ] && [ -d "$NEGATIVE_STAGE" ]; then
    /bin/rm -rf -- "$NEGATIVE_STAGE"
  fi
}
trap cleanup EXIT

ok() {
  echo "ok: $1"
}

bad() {
  echo "FAIL: $1"
  fail=1
}

check() {
  local description="$1"
  shift
  if "$@"; then
    ok "$description"
  else
    bad "$description"
  fi
}

expect_failure() {
  local description="$1"
  shift
  if "$@"; then
    bad "$description"
  else
    ok "$description"
  fi
}

if [ -n "${GOTOSLEEP_RELEASE_VERSION-}" ]; then
  validate_release_version "$GOTOSLEEP_RELEASE_VERSION"
  VERSION="$GOTOSLEEP_RELEASE_VERSION"
else
  query_release_version "$TMP/version"
  VERSION="$RELEASE_VERSION"
fi
PACKAGE_NAME="GoToSleep-$VERSION.pkg"
PKG="${PKG:-target/release/$PACKAGE_NAME}"
RELEASE_DIR="${RELEASE_DIR:-$(dirname "$PKG")}"
CHECKSUM_FILE="$RELEASE_DIR/SHA256SUMS"

[ -f "$PKG" ] || {
  echo "FAIL: $PKG missing" >&2
  exit 1
}
PKG_PATH="$(cd "$(dirname "$PKG")" && pwd -P)/$(basename "$PKG")"
[ "$(basename "$PKG")" = "$PACKAGE_NAME" ] || {
  echo "FAIL: unexpected package name: $PKG" >&2
  exit 1
}

EXPANDED="$TMP/expanded"
PAYLOAD="$TMP/payload"
RAW="$TMP/raw"
XAR_TOC="$TMP/xar-toc.xml"
/bin/mkdir -p "$PAYLOAD" "$RAW"
/usr/sbin/pkgutil --expand "$PKG" "$EXPANDED"
/usr/bin/gzip -dc "$EXPANDED/Payload" |
  (cd "$PAYLOAD" && /usr/bin/cpio -idm --quiet)
/usr/bin/xar --dump-toc="$XAR_TOC" -f "$PKG"
(cd "$RAW" && /usr/bin/xar -xf "$PKG_PATH" Scripts)

PAYLOAD_FILES="$TMP/payload-files"
NORMALIZED_FILES="$TMP/payload-normalized"
EXPECTED_FILES="$TMP/payload-expected"
BOM="$EXPANDED/Bom"
BOM_LIST="$TMP/bom.tsv"
BOM_PATHS="$TMP/bom-paths"
EXPECTED_BOM_PATHS="$TMP/bom-paths-expected"
SCRIPTS_LIST="$TMP/scripts"
EXPECTED_SCRIPTS="$TMP/scripts-expected"
SCRIPTS_CPIO_LIST="$TMP/scripts-cpio-list"

/usr/bin/gzip -dc "$RAW/Scripts" |
  /usr/bin/cpio --numeric-uid-gid -itv > "$SCRIPTS_CPIO_LIST" 2>"$TMP/scripts-cpio.err"

/usr/sbin/pkgutil --payload-files "$PKG" > "$PAYLOAD_FILES"
/usr/bin/sed -e 's#^\./##' -e '/^\.$/d' "$PAYLOAD_FILES" |
  LC_ALL=C /usr/bin/sort > "$NORMALIZED_FILES"
/bin/cat > "$EXPECTED_FILES" <<'EOF'
Applications
Applications/GoToSleep.app
Applications/GoToSleep.app/Contents
Applications/GoToSleep.app/Contents/Info.plist
Applications/GoToSleep.app/Contents/MacOS
Applications/GoToSleep.app/Contents/MacOS/gotosleep-agent
Applications/GoToSleep.app/Contents/_CodeSignature
Applications/GoToSleep.app/Contents/_CodeSignature/CodeResources
Library
Library/Application Support
Library/Application Support/GoToSleep
Library/Application Support/GoToSleep/bin
Library/Application Support/GoToSleep/bin/gotosleep-lock
Library/Application Support/GoToSleep/bin/gotosleepd
Library/LaunchAgents
Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist
Library/LaunchDaemons
Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist
EOF

check "payload inventory is exact" /usr/bin/cmp -s "$EXPECTED_FILES" "$NORMALIZED_FILES"
if /usr/bin/grep -Eq '(^|/)\._' "$PAYLOAD_FILES" ||
  /usr/bin/find "$EXPANDED" -name '._*' -print -quit | /usr/bin/grep -q . ||
  /usr/bin/find "$PAYLOAD" -name '._*' -print -quit | /usr/bin/grep -q .; then
  bad "archive contains no AppleDouble entries"
else
  ok "archive contains no AppleDouble entries"
fi
if /usr/bin/grep -q 'state\.edn' "$PAYLOAD_FILES"; then
  bad "state.edn is absent from the payload"
else
  ok "state.edn is absent from the payload"
fi

/usr/bin/lsbom -s "$BOM" > "$BOM_PATHS"
{
  printf '.\n'
  /usr/bin/sed 's#^#./#' "$EXPECTED_FILES"
} > "$EXPECTED_BOM_PATHS"
check "BOM inventory is exact" /usr/bin/cmp -s "$EXPECTED_BOM_PATHS" "$BOM_PATHS"

/usr/bin/lsbom -p MUGf "$BOM" > "$BOM_LIST"
if /usr/bin/awk -F '\t' '$2 != "root" || $3 != "wheel" {exit 1}' "$BOM_LIST"; then
  ok "every BOM entry is root:wheel"
else
  bad "every BOM entry is root:wheel"
fi

check_bom() {
  local path="$1"
  local expected_mode="$2"
  local row
  local expected
  row="$(/usr/bin/awk -F '\t' -v p="$path" '$4 == p {print $1 "\t" $2 "\t" $3}' "$BOM_LIST")"
  expected="$(printf '%s\troot\twheel' "$expected_mode")"
  if [ "$row" = "$expected" ]; then
    ok "BOM $path is $expected_mode root:wheel"
  else
    bad "BOM $path is $expected_mode root:wheel (got ${row:-missing})"
  fi
}

while IFS='|' read -r mode path; do
  check_bom "$path" "$mode"
done <<'EOF'
drwxr-xr-x |.
drwxr-xr-x |./Applications
drwxr-xr-x |./Applications/GoToSleep.app
drwxr-xr-x |./Applications/GoToSleep.app/Contents
-rw-r--r-- |./Applications/GoToSleep.app/Contents/Info.plist
drwxr-xr-x |./Applications/GoToSleep.app/Contents/MacOS
-rwxr-xr-x |./Applications/GoToSleep.app/Contents/MacOS/gotosleep-agent
drwxr-xr-x |./Applications/GoToSleep.app/Contents/_CodeSignature
-rw-r--r-- |./Applications/GoToSleep.app/Contents/_CodeSignature/CodeResources
drwxr-xr-x |./Library
drwxr-xr-x |./Library/Application Support
drwxr-xr-x |./Library/Application Support/GoToSleep
drwxr-xr-x |./Library/Application Support/GoToSleep/bin
-rwxr-xr-x |./Library/Application Support/GoToSleep/bin/gotosleep-lock
-rwxr-xr-x |./Library/Application Support/GoToSleep/bin/gotosleepd
drwxr-xr-x |./Library/LaunchAgents
-rw-r--r-- |./Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist
drwxr-xr-x |./Library/LaunchDaemons
-rw-r--r-- |./Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist
EOF

/usr/bin/find "$EXPANDED/Scripts" -mindepth 1 -maxdepth 1 -type f -print |
  /usr/bin/sed 's#.*/##' |
  LC_ALL=C /usr/bin/sort > "$SCRIPTS_LIST"
printf '%s\n' check-existing-app postinstall preinstall |
  LC_ALL=C /usr/bin/sort > "$EXPECTED_SCRIPTS"
check "installer script inventory is exact" /usr/bin/cmp -s "$EXPECTED_SCRIPTS" "$SCRIPTS_LIST"
check "every Scripts CPIO entry is root:wheel" \
  /usr/bin/awk '$3 != "0" || $4 != "0" {exit 1}' "$SCRIPTS_CPIO_LIST"
for script in check-existing-app postinstall preinstall; do
  check "archived $script matches source" \
    /usr/bin/cmp -s "$EXPANDED/Scripts/$script" "$ROOT/packaging/scripts/$script"
  check "archived $script mode is 0755" \
    test "$(/usr/bin/stat -f %Lp "$EXPANDED/Scripts/$script")" = 755
done

INFO="$PAYLOAD/Applications/GoToSleep.app/Contents/Info.plist"
APP="$PAYLOAD/Applications/GoToSleep.app"
APP_EXEC="$APP/Contents/MacOS/gotosleep-agent"
DAEMON="$PAYLOAD/Library/Application Support/GoToSleep/bin/gotosleepd"
LOCK="$PAYLOAD/Library/Application Support/GoToSleep/bin/gotosleep-lock"
AGENT_PLIST="$PAYLOAD/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
DAEMON_PLIST="$PAYLOAD/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist"

check "archived Info.plist is valid" /usr/bin/plutil -lint "$INFO"
check "archived agent plist is valid" /usr/bin/plutil -lint "$AGENT_PLIST"
check "archived daemon plist is valid" /usr/bin/plutil -lint "$DAEMON_PLIST"
check "source Info.plist is valid" /usr/bin/plutil -lint "$ROOT/packaging/Info.plist"
check "source agent plist is valid" \
  /usr/bin/plutil -lint "$ROOT/packaging/com.rubberducking.gotosleep.agent.plist"
check "source daemon plist is valid" \
  /usr/bin/plutil -lint "$ROOT/packaging/com.rubberducking.gotosleep.daemon.plist"

check "Info.plist has LSUIElement=true" \
  test "$(/usr/bin/plutil -extract LSUIElement raw -o - "$INFO")" = true
check "Info.plist bundle id is exact" \
  test "$(/usr/bin/plutil -extract CFBundleIdentifier raw -o - "$INFO")" = \
    com.rubberducking.gotosleep
check "Info.plist short version is authoritative" \
  test "$(/usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$INFO")" = "$VERSION"
check "Info.plist bundle version is authoritative" \
  test "$(/usr/bin/plutil -extract CFBundleVersion raw -o - "$INFO")" = "$VERSION"
expect_failure "source Info.plist has no production short-version literal" \
  /usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$ROOT/packaging/Info.plist"
expect_failure "source Info.plist has no production bundle-version literal" \
  /usr/bin/plutil -extract CFBundleVersion raw -o - "$ROOT/packaging/Info.plist"
check "Info.plist minimum system version is exact" \
  test "$(/usr/bin/plutil -extract LSMinimumSystemVersion raw -o - "$INFO")" = 26.0

check "agent label is exact" \
  test "$(/usr/bin/plutil -extract Label raw -o - "$AGENT_PLIST")" = \
    com.rubberducking.gotosleep.agent
check "agent executable path is exact" \
  test "$(/usr/bin/plutil -extract ProgramArguments.0 raw -o - "$AGENT_PLIST")" = \
    /Applications/GoToSleep.app/Contents/MacOS/gotosleep-agent
check "agent associated bundle id is the app id" \
  test "$(/usr/bin/plutil -extract AssociatedBundleIdentifiers raw -o - "$AGENT_PLIST")" = \
    com.rubberducking.gotosleep
check "daemon label is exact" \
  test "$(/usr/bin/plutil -extract Label raw -o - "$DAEMON_PLIST")" = \
    com.rubberducking.gotosleep.daemon
check "daemon executable path is exact" \
  test "$(/usr/bin/plutil -extract ProgramArguments.0 raw -o - "$DAEMON_PLIST")" = \
    "/Library/Application Support/GoToSleep/bin/gotosleepd"

PACKAGE_INFO="$EXPANDED/PackageInfo"
check "PackageInfo is valid XML" /usr/bin/xmllint --noout "$PACKAGE_INFO"
check "package identifier is exact" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/@identifier)' "$PACKAGE_INFO")" = \
    com.rubberducking.gotosleep.pkg
check "package version is authoritative" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/@version)' "$PACKAGE_INFO")" = "$VERSION"
check "package install location is root" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/@install-location)' "$PACKAGE_INFO")" = /
check "package authorization is root" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/@auth)' "$PACKAGE_INFO")" = root
expected_payload_count="$(( $(/usr/bin/wc -l < "$EXPECTED_FILES" | /usr/bin/tr -d ' ') + 1 ))"
check "PackageInfo payload count matches exact inventory" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/payload/@numberOfFiles)' "$PACKAGE_INFO")" = \
    "$expected_payload_count"
check "PackageInfo bundle id is exact" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/bundle/@id)' "$PACKAGE_INFO")" = \
    com.rubberducking.gotosleep
check "PackageInfo bundle short version is authoritative" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/bundle/@CFBundleShortVersionString)' "$PACKAGE_INFO")" = \
    "$VERSION"
check "PackageInfo bundle version is authoritative" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/bundle/@CFBundleVersion)' "$PACKAGE_INFO")" = \
    "$VERSION"
check "PackageInfo strict bundle id is exact" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/strict-identifier/bundle/@id)' "$PACKAGE_INFO")" = \
    com.rubberducking.gotosleep
check "component is not relocatable in archive" \
  test "$(/usr/bin/xmllint --xpath 'string(/pkg-info/@relocatable)' "$PACKAGE_INFO")" = false
check "component is not version-checked in archive" \
  test "$(/usr/bin/xmllint --xpath 'count(/pkg-info/bundle-version/bundle)' "$PACKAGE_INFO")" = 0
check "component source uses strict bundle identifiers" \
  test "$(/usr/bin/plutil -extract 0.BundleHasStrictIdentifier raw -o - "$ROOT/packaging/component.plist")" = true
check "component source is not relocatable" \
  test "$(/usr/bin/plutil -extract 0.BundleIsRelocatable raw -o - "$ROOT/packaging/component.plist")" = false
check "component source is not version-checked" \
  test "$(/usr/bin/plutil -extract 0.BundleIsVersionChecked raw -o - "$ROOT/packaging/component.plist")" = false
check "component source replaces bundles as upgrades" \
  test "$(/usr/bin/plutil -extract 0.BundleOverwriteAction raw -o - "$ROOT/packaging/component.plist")" = upgrade

for binary in "$APP_EXEC" "$DAEMON" "$LOCK"; do
  check "$(basename "$binary") is arm64-only" \
    test "$(/usr/bin/lipo -archs "$binary")" = arm64
done

audit_signature() {
  local path="$1"
  local identifier="$2"
  local label="$3"
  local details="$TMP/codesign-$(basename "$path").txt"

  if /usr/bin/codesign --verify --strict --verbose=2 "$path" >"$details.verify" 2>&1; then
    ok "$label passes strict signature verification"
  else
    bad "$label passes strict signature verification"
    /usr/bin/sed 's/^/  /' "$details.verify"
  fi
  if /usr/bin/codesign --display --verbose=4 "$path" >"$details" 2>&1; then
    check "$label signing identifier is exact" \
      /usr/bin/grep -Fqx "Identifier=$identifier" "$details"
    check "$label signature is ad-hoc" \
      /usr/bin/grep -Fqx "Signature=adhoc" "$details"
    check "$label has no Team ID" \
      /usr/bin/grep -Fqx "TeamIdentifier=not set" "$details"
    expect_failure "$label has no certificate authority" \
      /usr/bin/grep -q '^Authority=' "$details"
    expect_failure "$label has no signature timestamp" \
      /usr/bin/grep -q '^Timestamp=' "$details"
  else
    bad "$label signature details are readable"
  fi
}

audit_signature "$DAEMON" com.rubberducking.gotosleep.daemon gotosleepd
audit_signature "$LOCK" com.rubberducking.gotosleep.lock gotosleep-lock
audit_signature "$APP" com.rubberducking.gotosleep GoToSleep.app
audit_signature "$APP_EXEC" com.rubberducking.gotosleep gotosleep-agent

TAMPERED_APP="$TMP/tampered/GoToSleep.app"
/bin/mkdir -p "$TMP/tampered"
/usr/bin/ditto --noextattr --noqtn "$APP" "$TAMPERED_APP"
/usr/bin/plutil -replace CFBundleName -string "Go To Sleep Tampered" \
  "$TAMPERED_APP/Contents/Info.plist"
if /usr/bin/codesign --verify --strict "$TAMPERED_APP" >"$TMP/tamper.out" 2>&1; then
  bad "post-signing app resource mutation is rejected"
else
  ok "post-signing app resource mutation is rejected"
fi

check "XAR TOC is valid XML" /usr/bin/xmllint --noout "$XAR_TOC"
check "XAR contains no signature element" \
  test "$(/usr/bin/xmllint --xpath 'count(//*[local-name() = "signature"])' "$XAR_TOC")" = 0
check "XAR files contain only distribution-safe properties" \
  test "$(/usr/bin/xmllint --xpath \
    'count(/xar/toc/file/*[not(self::name or self::type or self::mode or self::data)])' \
    "$XAR_TOC")" = 0
check "XAR contains no owner or extended-attribute properties" \
  test "$(/usr/bin/xmllint --xpath \
    'count(//*[local-name() = "uid" or local-name() = "user" or local-name() = "gid" or local-name() = "group" or local-name() = "ea" or local-name() = "acl" or local-name() = "FinderCreateTime"])' \
    "$XAR_TOC")" = 0
check "XAR leaves Payload uncompressed at the outer layer" \
  test "$(/usr/bin/xmllint --xpath \
    'string(/xar/toc/file[name = "Payload"]/data/encoding/@style)' "$XAR_TOC")" = \
    application/octet-stream
check "XAR leaves Scripts uncompressed at the outer layer" \
  test "$(/usr/bin/xmllint --xpath \
    'string(/xar/toc/file[name = "Scripts"]/data/encoding/@style)' "$XAR_TOC")" = \
    application/octet-stream
if LC_ALL=C /usr/bin/grep -Eqi \
  'com[.]apple|provenance|quarantine|extended.?attribute|<signature' "$XAR_TOC"; then
  bad "XAR TOC contains no provenance, quarantine, extended attributes, or signature"
else
  ok "XAR TOC contains no provenance, quarantine, extended attributes, or signature"
fi

/usr/bin/xar -tf "$PKG" | LC_ALL=C /usr/bin/sort > "$TMP/xar-members"
printf '%s\n' Bom PackageInfo Payload Scripts |
  LC_ALL=C /usr/bin/sort > "$TMP/xar-members-expected"
check "XAR member inventory is exact" \
  /usr/bin/cmp -s "$TMP/xar-members-expected" "$TMP/xar-members"

set +e
LC_ALL=C /usr/sbin/pkgutil --check-signature "$PKG" >"$TMP/pkg-signature.out" 2>&1
pkgutil_status=$?
LC_ALL=C /usr/sbin/spctl --ignore-cache --no-cache --assess --type install "$PKG" \
  >"$TMP/spctl.out" 2>&1
spctl_status=$?
set -e
check "pkgutil reports the expected unsigned-package exit status" test "$pkgutil_status" -eq 1
check "pkgutil reports no package signature" \
  /usr/bin/grep -Fqx '   Status: no signature' "$TMP/pkg-signature.out"
check "spctl reports the expected rejected-package exit status" test "$spctl_status" -eq 3
check "spctl reports package rejection" /usr/bin/grep -Eq ': rejected$' "$TMP/spctl.out"

check "release directory contains SHA256SUMS" test -f "$CHECKSUM_FILE"
expected_pair="$(printf '%s\n' "$PACKAGE_NAME" SHA256SUMS | LC_ALL=C /usr/bin/sort)"
actual_pair="$(
  /usr/bin/find "$RELEASE_DIR" -mindepth 1 -maxdepth 1 -print |
    /usr/bin/sed 's#.*/##' |
    LC_ALL=C /usr/bin/sort
)"
check "release directory contains exactly the package and checksum" \
  test "$actual_pair" = "$expected_pair"
digest="$(/usr/bin/shasum -a 256 "$PKG" | /usr/bin/awk '{print $1}')"
printf '%s  %s\n' "$digest" "$PACKAGE_NAME" > "$TMP/SHA256SUMS.expected"
check "SHA256SUMS has exact content and final newline" \
  /usr/bin/cmp -s "$TMP/SHA256SUMS.expected" "$CHECKSUM_FILE"
check "SHA256SUMS uses a lowercase SHA-256 digest and exact filename" \
  /usr/bin/grep -Eq "^[0-9a-f]{64}  ${PACKAGE_NAME//./\\.}$" "$CHECKSUM_FILE"
check "strict checksum verification passes" \
  /bin/bash -c 'cd "$1" && /usr/bin/shasum -a 256 --strict -c SHA256SUMS' \
  bash "$RELEASE_DIR"

PREINSTALL="$ROOT/packaging/scripts/preinstall"
helper_line="$(/usr/bin/grep -nF 'check-existing-app' "$PREINSTALL" | /usr/bin/head -1 | /usr/bin/cut -d: -f1)"
launchctl_line="$(/usr/bin/grep -nF '/bin/launchctl' "$PREINSTALL" | /usr/bin/head -1 | /usr/bin/cut -d: -f1)"
check "foreign-app preflight occurs before launchd mutation" \
  test "$helper_line" -lt "$launchctl_line"

APP_CHECK="$EXPANDED/Scripts/check-existing-app"
check "existing-app check accepts an absent path" \
  "$APP_CHECK" "$TMP/app-fixtures/absent.app"
/bin/mkdir -p "$TMP/app-fixtures/public.app/Contents"
/bin/cp -X "$INFO" "$TMP/app-fixtures/public.app/Contents/Info.plist"
check "existing-app check accepts the public bundle id" \
  "$APP_CHECK" "$TMP/app-fixtures/public.app"
/bin/mkdir -p "$TMP/app-fixtures/foreign.app/Contents"
/bin/cp -X "$INFO" "$TMP/app-fixtures/foreign.app/Contents/Info.plist"
/usr/bin/plutil -replace CFBundleIdentifier -string org.example.foreign \
  "$TMP/app-fixtures/foreign.app/Contents/Info.plist"
expect_failure "existing-app check rejects a foreign bundle id" \
  "$APP_CHECK" "$TMP/app-fixtures/foreign.app"
/bin/mkdir -p "$TMP/app-fixtures/malformed.app"
expect_failure "existing-app check rejects a malformed app" \
  "$APP_CHECK" "$TMP/app-fixtures/malformed.app"

query_command="jolt -e \"(require '[gotosleep.version :as v]) (print v/value)\""
check "packaging defines the exact authoritative version query once" \
  test "$(/usr/bin/grep -Fc "$query_command" "$ROOT/packaging/common.sh")" -eq 1
check "the standalone stage build queries the version once" \
  test "$(/usr/bin/grep -Ec '^[[:space:]]*query_release_version ' "$ROOT/packaging/build.sh")" -eq 1
check "the package build queries the version once" \
  test "$(/usr/bin/grep -Ec '^[[:space:]]*query_release_version ' "$ROOT/packaging/pkg.sh")" -eq 1
check "the package build passes its authoritative version to the audit" \
  /usr/bin/grep -Fq 'GOTOSLEEP_RELEASE_VERSION="$VERSION"' "$ROOT/packaging/pkg.sh"
if LC_ALL=C /usr/bin/grep -R -F "$VERSION" "$ROOT/packaging" >/dev/null; then
  bad "packaging contains no production version literal"
else
  ok "packaging contains no production version literal"
fi
check "stage build requests three signable outputs" \
  test "$(/usr/bin/grep -c 'jolt build --signable ' "$ROOT/packaging/build.sh")" -eq 3
deep_signing_flag='--de''ep'
if LC_ALL=C /usr/bin/grep -R -- "$deep_signing_flag" \
  "$ROOT/packaging" "$ROOT/dev/check-pkg.sh" >/dev/null; then
  bad "packaging never uses recursive code signing"
else
  ok "packaging never uses recursive code signing"
fi

legacy_pattern='local[.]goto''sleep'
if LC_ALL=C /usr/bin/grep -R -E "$legacy_pattern" "$ROOT/packaging" >/dev/null ||
  LC_ALL=C /usr/bin/grep -aE "$legacy_pattern" "$PKG" >/dev/null; then
  bad "public packaging source and package contain no prerelease identifier"
else
  ok "public packaging source and package contain no prerelease identifier"
fi

/bin/mkdir -p "$ROOT/target"
NEGATIVE_STAGE="$(/usr/bin/mktemp -d "$ROOT/target/.check-preflight.XXXXXX")"
printf 'keep\n' > "$NEGATIVE_STAGE/sentinel"
set +e
/usr/bin/env -u JOLT_CHEZ_CSV \
  PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
  /bin/bash "$ROOT/packaging/build.sh" --version "$VERSION" "$NEGATIVE_STAGE" \
  >"$TMP/preflight-negative.out" 2>&1
preflight_status=$?
set -e
check "missing Chez preflight fails before a signable build" test "$preflight_status" -eq 2
check "failed toolchain preflight preserves the existing staging path" \
  test "$(/bin/cat "$NEGATIVE_STAGE/sentinel")" = keep
check "missing Chez preflight explains the requirement" \
  /usr/bin/grep -Fq 'JOLT_CHEZ_CSV must name' "$TMP/preflight-negative.out"

canonical_release="$(canonical_path "$ROOT/target/release")"
actual_release="$(canonical_path "$RELEASE_DIR")"
if [ "${GOTOSLEEP_CHECK_INTERNAL-}" != 1 ] && [ "$actual_release" = "$canonical_release" ]; then
  before_refusal="$(
    /usr/bin/shasum -a 256 "$PKG" "$CHECKSUM_FILE"
    /usr/bin/find "$RELEASE_DIR" -mindepth 1 -maxdepth 1 -print |
      LC_ALL=C /usr/bin/sort
  )"
  set +e
  /bin/bash "$ROOT/packaging/pkg.sh" >"$TMP/existing-release.out" 2>&1
  refusal_status=$?
  set -e
  after_refusal="$(
    /usr/bin/shasum -a 256 "$PKG" "$CHECKSUM_FILE"
    /usr/bin/find "$RELEASE_DIR" -mindepth 1 -maxdepth 1 -print |
      LC_ALL=C /usr/bin/sort
  )"
  check "package build refuses an existing release directory" test "$refusal_status" -eq 2
  check "existing-release refusal is non-destructive" \
    test "$after_refusal" = "$before_refusal"
  check "existing-release refusal explains the conflict" \
    /usr/bin/grep -Fq 'refusing existing release path' "$TMP/existing-release.out"
fi

exit "$fail"
