#!/bin/bash
# Build, assemble, and ad-hoc sign an install-shaped staging root.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
# shellcheck source=packaging/common.sh
source "$SCRIPT_DIR/common.sh"
cd "$ROOT"

VERSION_OUTPUT=""
cleanup() {
  if [ -n "$VERSION_OUTPUT" ]; then
    /bin/rm -f -- "$VERSION_OUTPUT"
  fi
}
trap cleanup EXIT

if [ "${1-}" = "--version" ]; then
  [ "$#" -eq 3 ] ||
    packaging_die "usage: packaging/build.sh --version VERSION STAGE"
  VERSION="$2"
  STAGE_INPUT="$3"
  validate_release_version "$VERSION"
else
  [ "$#" -le 1 ] || packaging_die "usage: packaging/build.sh [STAGE]"
  STAGE_INPUT="${1:-target/stage}"
  VERSION_OUTPUT="$(/usr/bin/mktemp /tmp/gotosleep-version.XXXXXX)"
  query_release_version "$VERSION_OUTPUT"
  VERSION="$RELEASE_VERSION"
fi

require_signable_toolchain

TARGET_ROOT="$ROOT/target"
/bin/mkdir -p "$TARGET_ROOT"
if [ -L "$STAGE_INPUT" ]; then
  packaging_die "refusing a symlink staging path: $STAGE_INPUT"
fi
STAGE="$(canonical_path "$STAGE_INPUT")"
case "$STAGE" in
  "$TARGET_ROOT/release"|"$TARGET_ROOT/release"/*)
    packaging_die "refusing to use the published release path as staging: $STAGE"
    ;;
  "$TARGET_ROOT"/*)
    ;;
  *)
    packaging_die "refusing to replace a staging path outside $TARGET_ROOT: $STAGE"
    ;;
esac

/bin/rm -rf -- "$STAGE"
BIN="$STAGE/Library/Application Support/GoToSleep/bin"
APP="$STAGE/Applications/GoToSleep.app"
APP_EXEC="$APP/Contents/MacOS/gotosleep-agent"
DAEMON="$BIN/gotosleepd"
LOCK="$BIN/gotosleep-lock"
/bin/mkdir -p \
  "$BIN" \
  "$APP/Contents/MacOS" \
  "$STAGE/Library/LaunchDaemons" \
  "$STAGE/Library/LaunchAgents"

jolt build --signable -m gotosleep.daemon.main -o "$DAEMON"
jolt build --signable -m gotosleep.lock.main -o "$LOCK"
jolt build --signable -m gotosleep.agent.main -o "$APP_EXEC"

/bin/rm -rf -- "$DAEMON.build" "$LOCK.build" "$APP_EXEC.build"
if /usr/bin/find "$STAGE" -type d -name '*.build' -print -quit | /usr/bin/grep -q .; then
  packaging_die "a generated build directory remains in staging"
fi

/bin/cp -X "$SCRIPT_DIR/Info.plist" "$APP/Contents/Info.plist"
/bin/cp -X \
  "$SCRIPT_DIR/com.rubberducking.gotosleep.daemon.plist" \
  "$STAGE/Library/LaunchDaemons/"
/bin/cp -X \
  "$SCRIPT_DIR/com.rubberducking.gotosleep.agent.plist" \
  "$STAGE/Library/LaunchAgents/"
/usr/bin/plutil -insert CFBundleShortVersionString -string "$VERSION" \
  "$APP/Contents/Info.plist"
/usr/bin/plutil -insert CFBundleVersion -string "$VERSION" \
  "$APP/Contents/Info.plist"
/usr/bin/plutil -lint "$APP/Contents/Info.plist"

/bin/chmod 0755 "$DAEMON" "$LOCK" "$APP_EXEC"
/bin/chmod 0644 \
  "$APP/Contents/Info.plist" \
  "$STAGE/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist" \
  "$STAGE/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
/usr/bin/xattr -cr "$STAGE"

/usr/bin/codesign --force --sign - --timestamp=none \
  --identifier com.rubberducking.gotosleep.daemon "$DAEMON"
/usr/bin/codesign --force --sign - --timestamp=none \
  --identifier com.rubberducking.gotosleep.lock "$LOCK"
/usr/bin/codesign --force --sign - --timestamp=none "$APP"

verify_adhoc_signature "$DAEMON" com.rubberducking.gotosleep.daemon
verify_adhoc_signature "$LOCK" com.rubberducking.gotosleep.lock
verify_adhoc_signature "$APP" com.rubberducking.gotosleep
verify_adhoc_signature "$APP_EXEC" com.rubberducking.gotosleep

echo "staged version $VERSION in $STAGE"
