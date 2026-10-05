#!/bin/bash
# Build a validated, versioned release pair and publish it with one rename.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
# shellcheck source=packaging/common.sh
source "$SCRIPT_DIR/common.sh"
cd "$ROOT"

TARGET_ROOT="$ROOT/target"
RELEASE_DIR="$TARGET_ROOT/release"
if [ -e "$RELEASE_DIR" ] || [ -L "$RELEASE_DIR" ]; then
  packaging_die "refusing existing release path: $RELEASE_DIR"
fi
/bin/mkdir -p "$TARGET_ROOT"

VERSION_OUTPUT="$(/usr/bin/mktemp /tmp/gotosleep-version.XXXXXX)"
RELEASE_TMP=""
cleanup() {
  /bin/rm -f -- "$VERSION_OUTPUT"
  if [ -n "$RELEASE_TMP" ] && [ -d "$RELEASE_TMP" ]; then
    /bin/rm -rf -- "$RELEASE_TMP"
  fi
}
trap cleanup EXIT

query_release_version "$VERSION_OUTPUT"
VERSION="$RELEASE_VERSION"
PACKAGE_NAME="GoToSleep-$VERSION.pkg"
RELEASE_TMP="$(/usr/bin/mktemp -d "$TARGET_ROOT/.release.XXXXXX")"
WORK="$RELEASE_TMP/.work"
STAGE="$WORK/stage"
SCRIPT_STAGE="$WORK/scripts"
EXPANDED="$WORK/expanded"
PAYLOAD_ROOT="$WORK/payload-root"
OUTER="$WORK/outer"
/bin/mkdir -p "$WORK" "$SCRIPT_STAGE" "$PAYLOAD_ROOT" "$OUTER"

/bin/bash "$SCRIPT_DIR/build.sh" --version "$VERSION" "$STAGE"

/bin/cp -X \
  "$SCRIPT_DIR/scripts/preinstall" \
  "$SCRIPT_DIR/scripts/postinstall" \
  "$SCRIPT_DIR/scripts/check-existing-app" \
  "$SCRIPT_STAGE/"
/bin/chmod 0755 \
  "$SCRIPT_STAGE/preinstall" \
  "$SCRIPT_STAGE/postinstall" \
  "$SCRIPT_STAGE/check-existing-app"
/usr/bin/xattr -cr "$SCRIPT_STAGE"

COPYFILE_DISABLE=1 /usr/bin/pkgbuild \
  --root "$STAGE" \
  --install-location / \
  --identifier com.rubberducking.gotosleep.pkg \
  --version "$VERSION" \
  --scripts "$SCRIPT_STAGE" \
  --component-plist "$SCRIPT_DIR/component.plist" \
  "$WORK/pkgbuild.pkg"

/usr/sbin/pkgutil --expand "$WORK/pkgbuild.pkg" "$EXPANDED"
/usr/bin/gzip -dc "$EXPANDED/Payload" |
  (cd "$PAYLOAD_ROOT" && COPYFILE_DISABLE=1 /usr/bin/cpio -idm --quiet)

(cd "$PAYLOAD_ROOT" &&
  COPYFILE_DISABLE=1 /usr/bin/find . -print |
    LC_ALL=C /usr/bin/sort |
    COPYFILE_DISABLE=1 /usr/bin/cpio \
      -o -R root:wheel --format odc 2>"$WORK/cpio.log" |
    /usr/bin/gzip -9 > "$EXPANDED/Payload")

/usr/bin/lsbom "$EXPANDED/Bom" |
  /usr/bin/grep -vE '(^|/)\._' > "$WORK/bom.txt"
/usr/bin/mkbom -i "$WORK/bom.txt" "$EXPANDED/Bom"
/usr/bin/find "$EXPANDED/Scripts" -name '._*' -delete
file_count="$(/usr/bin/find "$PAYLOAD_ROOT" -print | /usr/bin/wc -l | /usr/bin/tr -d ' ')"
install_kb="$(/usr/bin/du -sk "$PAYLOAD_ROOT" | /usr/bin/awk '{print $1}')"
/usr/bin/sed -E -i '' \
  "s/numberOfFiles=\"[0-9]+\" installKBytes=\"[0-9]+\"/numberOfFiles=\"$file_count\" installKBytes=\"$install_kb\"/" \
  "$EXPANDED/PackageInfo"

COPYFILE_DISABLE=1 /usr/sbin/pkgutil --flatten "$EXPANDED" "$WORK/flattened.pkg"
(cd "$OUTER" && /usr/bin/xar -xf "$WORK/flattened.pkg")
(cd "$EXPANDED/Scripts" &&
  COPYFILE_DISABLE=1 /usr/bin/find . -print |
    LC_ALL=C /usr/bin/sort |
    COPYFILE_DISABLE=1 /usr/bin/cpio \
      -o -R root:wheel --format odc 2>"$WORK/scripts-cpio.log" |
    /usr/bin/gzip -9 > "$OUTER/Scripts")
/usr/bin/xattr -cr "$OUTER"
if /usr/bin/find "$OUTER" -name '._*' -print -quit | /usr/bin/grep -q .; then
  packaging_die "flattened package contains AppleDouble entries"
fi

expected_outer="$(printf '%s\n' Bom PackageInfo Payload Scripts | LC_ALL=C /usr/bin/sort)"
actual_outer="$(
  /usr/bin/find "$OUTER" -mindepth 1 -maxdepth 1 -print |
    /usr/bin/sed 's#.*/##' |
    LC_ALL=C /usr/bin/sort
)"
[ "$actual_outer" = "$expected_outer" ] ||
  packaging_die "flattened package has unexpected outer members"

(cd "$OUTER" &&
  COPYFILE_DISABLE=1 /usr/bin/xar --distribution \
    --no-compress 'Payload' \
    --no-compress 'Scripts' \
    -cf "$RELEASE_TMP/$PACKAGE_NAME" Bom PackageInfo Payload Scripts)

/bin/rm -rf -- "$WORK"
digest="$(/usr/bin/shasum -a 256 "$RELEASE_TMP/$PACKAGE_NAME" | /usr/bin/awk '{print $1}')"
printf '%s  %s\n' "$digest" "$PACKAGE_NAME" > "$RELEASE_TMP/SHA256SUMS"

expected_pair="$(printf '%s\n' "$PACKAGE_NAME" SHA256SUMS | LC_ALL=C /usr/bin/sort)"
actual_pair="$(
  /usr/bin/find "$RELEASE_TMP" -mindepth 1 -maxdepth 1 -print |
    /usr/bin/sed 's#.*/##' |
    LC_ALL=C /usr/bin/sort
)"
[ "$actual_pair" = "$expected_pair" ] ||
  packaging_die "release staging directory does not contain the exact release pair"

GOTOSLEEP_CHECK_INTERNAL=1 \
  GOTOSLEEP_RELEASE_VERSION="$VERSION" \
  PKG="$RELEASE_TMP/$PACKAGE_NAME" \
  RELEASE_DIR="$RELEASE_TMP" \
  /bin/bash "$ROOT/dev/check-pkg.sh"

if [ -e "$RELEASE_DIR" ] || [ -L "$RELEASE_DIR" ]; then
  packaging_die "release path appeared before publication: $RELEASE_DIR"
fi
/bin/mv "$RELEASE_TMP" "$RELEASE_DIR"
RELEASE_TMP=""
trap - EXIT
/bin/rm -f -- "$VERSION_OUTPUT"
echo "wrote $RELEASE_DIR/$PACKAGE_NAME"
echo "wrote $RELEASE_DIR/SHA256SUMS"
