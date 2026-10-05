#!/bin/bash
# Verify an existing local release tag in isolation and prepare its exact assets.
set -euo pipefail

die() {
  echo "Go To Sleep release check: $*" >&2
  exit 2
}

VERIFICATION_ONLY=0
if [ "${1-}" = --verification-only ]; then
  VERIFICATION_ONLY=1
  shift
fi

[ "$#" -eq 2 ] ||
  die "usage: dev/check-release.sh [--verification-only] TAG RELEASE_DATE"
TAG="$1"
RELEASE_DATE="$2"

[[ "$TAG" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
  die "tag must be canonical vMAJOR.MINOR.PATCH"

if [ "$VERIFICATION_ONLY" -eq 1 ]; then
  [ "$TAG" = v0.1.0 ] ||
    die "--verification-only is reserved for the accepted v0.1.0 bootstrap release"
elif [ "$TAG" = v0.1.0 ]; then
  die "v0.1.0 is an accepted immutable asset; use --verification-only to rebuild non-release evidence"
fi

/usr/bin/python3 - "$RELEASE_DATE" <<'PY' ||
import datetime
import sys

value = sys.argv[1]
try:
    parsed = datetime.date.fromisoformat(value)
except ValueError as exc:
    raise SystemExit(f"release date must be a real ISO YYYY-MM-DD date: {exc}")
if parsed.isoformat() != value:
    raise SystemExit("release date must be canonical ISO YYYY-MM-DD")
PY
  die "invalid release date: $RELEASE_DATE"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
GIT=/usr/bin/git

# The release decision must not inherit repository-selection or config-injection
# variables from the caller. This changes only this process and its children.
while IFS= read -r GIT_ENV_NAME; do
  [ -n "$GIT_ENV_NAME" ] || continue
  unset "$GIT_ENV_NAME"
done <<EOF
$(/usr/bin/env |
  /usr/bin/sed -nE 's/^(GIT_[A-Za-z0-9_]+)=.*/\1/p' |
  LC_ALL=C /usr/bin/sort -u)
EOF
export GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_SYSTEM=/dev/null
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_COUNT=0
export GIT_ATTR_NOSYSTEM=1
export GIT_NO_REPLACE_OBJECTS=1

[ "$("$GIT" -C "$ROOT" rev-parse --is-inside-work-tree 2>/dev/null)" = true ] ||
  die "the command must be run from a Git worktree"
[ "$("$GIT" -C "$ROOT" rev-parse --is-bare-repository 2>/dev/null)" = false ] ||
  die "bare repositories are not release worktrees"
TOPLEVEL="$("$GIT" -C "$ROOT" rev-parse --show-toplevel)"
[ "$(cd "$TOPLEVEL" && pwd -P)" = "$ROOT" ] ||
  die "dev/check-release.sh does not belong to this worktree root"
[ "$("$GIT" -C "$ROOT" symbolic-ref --quiet --short HEAD 2>/dev/null || true)" = main ] ||
  die "release checks require branch main"
[ -z "$("$GIT" -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ] ||
  die "release checks require a clean worktree and index"

TAG_REF="refs/tags/$TAG"
"$GIT" -C "$ROOT" show-ref --verify --quiet "$TAG_REF" ||
  die "local tag does not exist: $TAG"
[ "$("$GIT" -C "$ROOT" cat-file -t "$TAG_REF")" = tag ] ||
  die "release tag must be annotated: $TAG"

HEAD_COMMIT="$("$GIT" -C "$ROOT" rev-parse --verify HEAD^{commit})"
TAG_COMMIT="$("$GIT" -C "$ROOT" rev-parse --verify "$TAG_REF^{commit}")"
[ "$TAG_COMMIT" = "$HEAD_COMMIT" ] ||
  die "release tag does not peel to main HEAD"
TAG_OBJECT="$("$GIT" -C "$ROOT" rev-parse --verify "$TAG_REF^{object}")"

VERSION="${TAG#v}"
VERSION_LINES="$(
  /usr/bin/sed -nE 's/^\(def value "([^"]+)"\)$/\1/p' \
    "$ROOT/src/gotosleep/version.clj"
)"
[ "$(printf '%s\n' "$VERSION_LINES" | /usr/bin/awk 'NF {count++} END {print count+0}')" -eq 1 ] ||
  die "src/gotosleep/version.clj must contain one exact production version definition"
[ "$VERSION_LINES" = "$VERSION" ] ||
  die "tag and production version disagree: $TAG versus $VERSION_LINES"

EXPECTED_TAG_SUBJECT="Go To Sleep $VERSION"
TAG_SUBJECT="$("$GIT" -C "$ROOT" for-each-ref --format='%(contents:subject)' "$TAG_REF")"
[ "$TAG_SUBJECT" = "$EXPECTED_TAG_SUBJECT" ] ||
  die "annotated tag subject must be exactly: $EXPECTED_TAG_SUBJECT"

CHANGELOG="$ROOT/CHANGELOG.md"
EXPECTED_CHANGELOG_HEADING="## [$VERSION] - $RELEASE_DATE"
[ "$(/usr/bin/grep -Fxc "$EXPECTED_CHANGELOG_HEADING" "$CHANGELOG" || true)" -eq 1 ] ||
  die "CHANGELOG.md must contain exactly one $EXPECTED_CHANGELOG_HEADING heading"
[ "$(
  /usr/bin/awk -v version="$VERSION" '
    $0 == "## [" version "]" ||
    index($0, "## [" version "] - ") == 1 { count++ }
    END { print count + 0 }
  ' "$CHANGELOG"
)" -eq 1 ] ||
  die "CHANGELOG.md contains a missing or conflicting $VERSION release heading"

PACKAGE_NAME="GoToSleep-$VERSION.pkg"
README_PACKAGES="$(
  /usr/bin/grep -Eo 'GoToSleep-[0-9]+\.[0-9]+\.[0-9]+\.pkg' "$ROOT/README.md" |
    LC_ALL=C /usr/bin/sort -u || true
)"
[ -n "$README_PACKAGES" ] ||
  die "README.md contains no versioned package example"
[ "$README_PACKAGES" = "$PACKAGE_NAME" ] ||
  die "README.md package examples do not all name $PACKAGE_NAME"

RELEASE_SPEC="$ROOT/specs/current/release.md"
[ -f "$RELEASE_SPEC" ] || die "missing current release spec: specs/current/release.md"
for marker in "$VERSION" "$TAG" "$RELEASE_DATE" "$PACKAGE_NAME"; do
  /usr/bin/grep -Fq "$marker" "$RELEASE_SPEC" ||
    die "current release spec does not identify $marker"
done

PUBLIC_REMOTE_URL=""
if [ "$VERIFICATION_ONLY" -eq 0 ]; then
  ORIGIN_URL_COUNT="$(
    {
      "$GIT" -C "$ROOT" config --local --name-only \
        --get-regexp '^remote\.origin\.url$' 2>/dev/null || true
    } |
      /usr/bin/wc -l |
      /usr/bin/tr -d ' '
  )"
  [ "$ORIGIN_URL_COUNT" -eq 1 ] ||
    die "normal releases require exactly one repository-local remote.origin.url"
  PUBLIC_REMOTE_URL="$("$GIT" -C "$ROOT" config --local --get remote.origin.url)"
  [ -n "$PUBLIC_REMOTE_URL" ] ||
    die "normal releases require a non-empty repository-local remote.origin.url"
  case "$PUBLIC_REMOTE_URL" in
    *'
'*) die "remote.origin.url must be a single line" ;;
  esac

  if "$GIT" -C "$ROOT" config --local --get-all remote.origin.pushurl \
      >/dev/null 2>&1; then
    die "remove repository-local remote.origin.pushurl before a release check"
  fi
  if "$GIT" -C "$ROOT" config --local --get-all remote.origin.mirror \
      >/dev/null 2>&1; then
    die "remove repository-local remote.origin.mirror before a release check"
  fi
  if "$GIT" -C "$ROOT" config --local --name-only \
      --get-regexp '^url\..*\.(insteadof|pushinsteadof)$' \
      >/dev/null 2>&1; then
    die "remove repository-local url.* rewrite configuration before a release check"
  fi

  set +e
  "$GIT" -C "$ROOT" ls-remote --exit-code --refs \
    "$PUBLIC_REMOTE_URL" "$TAG_REF" >/dev/null 2>&1
  ORIGIN_TAG_STATUS=$?
  set -e
  case "$ORIGIN_TAG_STATUS" in
    0)
      die "$TAG is already present at the explicit origin URL: $PUBLIC_REMOTE_URL"
      ;;
    2)
      ;;
    *)
      die "could not prove that $TAG is absent from the explicit origin URL: $PUBLIC_REMOTE_URL"
      ;;
  esac
fi

WORK_ROOT="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/gotosleep-release-check.XXXXXX")"
RESULT_PARENT=""
KEEP_RESULT=0

cleanup_paths() {
  if [ -n "$WORK_ROOT" ]; then
    /bin/rm -rf -- "$WORK_ROOT" || true
  fi
  if [ "$KEEP_RESULT" -ne 1 ] && [ -n "$RESULT_PARENT" ]; then
    /bin/rm -rf -- "$RESULT_PARENT" || true
  fi
}

on_exit() {
  status=$?
  trap - EXIT HUP INT TERM
  cleanup_paths
  exit "$status"
}

on_signal() {
  status="$1"
  trap - EXIT HUP INT TERM
  cleanup_paths
  exit "$status"
}

trap on_exit EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

EMPTY_HOME="$WORK_ROOT/home"
EMPTY_TEMPLATE="$WORK_ROOT/git-template"
CHECKOUT="$WORK_ROOT/checkout"
/bin/mkdir -p "$EMPTY_HOME" "$EMPTY_TEMPLATE"

isolated_git() {
  env \
    HOME="$EMPTY_HOME" \
    XDG_CONFIG_HOME="$EMPTY_HOME/.config" \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_SYSTEM=/dev/null \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_COUNT=0 \
    GIT_ATTR_NOSYSTEM=1 \
    GIT_NO_REPLACE_OBJECTS=1 \
    GIT_TEMPLATE_DIR="$EMPTY_TEMPLATE" \
    GIT_TERMINAL_PROMPT=0 \
    "$GIT" -c core.hooksPath=/dev/null "$@"
}

isolated_git init --quiet --initial-branch=release-check "$CHECKOUT"
ISOLATED_TAG_REF="refs/gotosleep-release-check/tag"
isolated_git -C "$CHECKOUT" fetch --quiet --no-tags --no-write-fetch-head \
  "$ROOT" "$TAG_REF:$ISOLATED_TAG_REF"
[ "$(isolated_git -C "$CHECKOUT" cat-file -t "$ISOLATED_TAG_REF")" = tag ] ||
  die "isolated fetch did not retain the annotated tag object"
[ "$(isolated_git -C "$CHECKOUT" rev-parse "$ISOLATED_TAG_REF^{object}")" = "$TAG_OBJECT" ] ||
  die "isolated tag object differs from the audited local tag"
[ "$(isolated_git -C "$CHECKOUT" rev-parse "$ISOLATED_TAG_REF^{commit}")" = "$TAG_COMMIT" ] ||
  die "isolated tag commit differs from the audited local tag"
isolated_git -C "$CHECKOUT" checkout --quiet --detach "$TAG_COMMIT"
[ "$(isolated_git -C "$CHECKOUT" rev-parse HEAD)" = "$TAG_COMMIT" ] ||
  die "isolated checkout is not at the tagged commit"
[ -z "$(isolated_git -C "$CHECKOUT" status --porcelain=v1 --untracked-files=all)" ] ||
  die "isolated checkout is unexpectedly dirty"

(
  cd "$CHECKOUT"
  jolt -M:test
  jolt -M:smoke
  /bin/bash dev/e2e/run.sh
  jolt -A:test -m gotosleep.agent.cancel-smoke
  jolt pkg
  /bin/bash dev/check-pkg.sh
  (
    cd target/release
    /usr/bin/shasum -a 256 --strict -c SHA256SUMS
  )
  /bin/bash dev/check-provenance.sh \
    --release-dir target/release \
    --build-root "$CHECKOUT"
)

RELEASE_DIR="$CHECKOUT/target/release"
PACKAGE="$RELEASE_DIR/$PACKAGE_NAME"
CHECKSUM="$RELEASE_DIR/SHA256SUMS"
[ -f "$PACKAGE" ] && [ ! -L "$PACKAGE" ] ||
  die "package build did not produce a regular $PACKAGE_NAME"
[ -f "$CHECKSUM" ] && [ ! -L "$CHECKSUM" ] ||
  die "package build did not produce a regular SHA256SUMS"

if [ "$VERIFICATION_ONLY" -eq 1 ]; then
  RESULT_PARENT="$(
    /usr/bin/mktemp -d \
      "${TMPDIR:-/tmp}/gotosleep-rebuilt-evidence-${TAG}-${TAG_COMMIT}.XXXXXX"
  )"
  RESULT_DIR="$RESULT_PARENT/rebuilt-evidence-${TAG}-${TAG_COMMIT}"
  REBUILT_PACKAGE_NAME="GoToSleep-$VERSION.rebuilt.pkg"
  /bin/mkdir "$RESULT_DIR"
  /bin/cp -X "$PACKAGE" "$RESULT_DIR/$REBUILT_PACKAGE_NAME"
  (
    cd "$RESULT_DIR"
    /usr/bin/shasum -a 256 "$REBUILT_PACKAGE_NAME" >SHA256SUMS.rebuilt
    /usr/bin/shasum -a 256 --strict -c SHA256SUMS.rebuilt
  )

  EXPECTED_RESULT_INVENTORY="$(
    printf '%s\n' SHA256SUMS.rebuilt "$REBUILT_PACKAGE_NAME" |
      LC_ALL=C /usr/bin/sort
  )"
else
  RESULT_PARENT="$(
    /usr/bin/mktemp -d \
      "${TMPDIR:-/tmp}/gotosleep-release-${TAG}-${TAG_COMMIT}.XXXXXX"
  )"
  RESULT_DIR="$RESULT_PARENT/handoff-${TAG}-${TAG_COMMIT}"
  /bin/mkdir "$RESULT_DIR"
  /bin/cp -X "$PACKAGE" "$CHECKSUM" "$RESULT_DIR/"

  EXPECTED_RESULT_INVENTORY="$(
    printf '%s\n' SHA256SUMS "$PACKAGE_NAME" | LC_ALL=C /usr/bin/sort
  )"
  (
    cd "$RESULT_DIR"
    /usr/bin/shasum -a 256 --strict -c SHA256SUMS
  )
fi

ACTUAL_RESULT_INVENTORY="$(
  /usr/bin/find "$RESULT_DIR" -mindepth 1 -maxdepth 1 -print |
    /usr/bin/sed 's#.*/##' |
    LC_ALL=C /usr/bin/sort
)"
[ "$ACTUAL_RESULT_INVENTORY" = "$EXPECTED_RESULT_INVENTORY" ] ||
  die "result directory does not contain the exact expected pair"

[ "$("$GIT" -C "$ROOT" rev-parse HEAD)" = "$HEAD_COMMIT" ] ||
  die "caller repository HEAD changed during release verification"
[ "$("$GIT" -C "$ROOT" rev-parse "$TAG_REF^{object}")" = "$TAG_OBJECT" ] ||
  die "caller repository tag changed during release verification"
[ -z "$("$GIT" -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ] ||
  die "caller repository changed during release verification"

/bin/rm -rf -- "$WORK_ROOT"
WORK_ROOT=""

# The result is complete and belongs to the caller before any success or
# publication text is emitted. From this point onward, output failures and
# terminal signals must not roll back a handoff that may already have been
# reported.
KEEP_RESULT=1
trap - EXIT HUP INT TERM

if [ "$VERIFICATION_ONLY" -eq 1 ]; then
  printf 'verification-only rebuild check passed for %s at %s (%s)\n' \
    "$TAG" "$RELEASE_DATE" "$TAG_COMMIT"
  printf 'REBUILT_EVIDENCE_DIR=%s\n' "$RESULT_DIR"
else
  printf 'release check passed for %s at %s (%s)\n' \
    "$TAG" "$RELEASE_DATE" "$TAG_COMMIT"
  printf 'HANDOFF_DIR=%s\n' "$RESULT_DIR"
  printf '%s\n' \
    'Push only after reviewing this handoff. From this verified main checkout, run:'
  printf '(\n'
  printf '%s\n' '  set -euo pipefail'
  printf '  PUBLIC_REMOTE_URL=%q\n' "$PUBLIC_REMOTE_URL"
  printf '  EXPECTED_MAIN_OBJECT=%q\n' "$HEAD_COMMIT"
  printf '  EXPECTED_TAG_OBJECT=%q\n' "$TAG_OBJECT"
  printf '  RELEASE_TAG_REF=%q\n' "$TAG_REF"
  printf '%s\n' \
    '  SOURCE_REPOSITORY="$(pwd -P)"' \
    '  PUBLISH_ROOT=""' \
    '' \
    '  cleanup_publish() {' \
    '    if [ -n "$PUBLISH_ROOT" ] && [ -d "$PUBLISH_ROOT" ]; then' \
    '      /bin/rm -rf -- "$PUBLISH_ROOT"' \
    '    fi' \
    '  }' \
    '' \
    '  publish_exit() {' \
    '    PUBLISH_STATUS=$?' \
    '    trap - EXIT HUP INT TERM' \
    '    cleanup_publish' \
    '    exit "$PUBLISH_STATUS"' \
    '  }' \
    '' \
    '  publish_signal() {' \
    '    PUBLISH_STATUS="$1"' \
    '    trap - EXIT HUP INT TERM' \
    '    cleanup_publish' \
    '    exit "$PUBLISH_STATUS"' \
    '  }' \
    '' \
    '  trap publish_exit EXIT' \
    '  trap '\''publish_signal 129'\'' HUP' \
    '  trap '\''publish_signal 130'\'' INT' \
    '  trap '\''publish_signal 143'\'' TERM' \
    '' \
    '  while IFS= read -r GIT_ENV_NAME; do' \
    '    [ -n "$GIT_ENV_NAME" ] && unset "$GIT_ENV_NAME"' \
    '  done <<EOF' \
    '$(/usr/bin/env | /usr/bin/sed -nE '\''s/^(GIT_[A-Za-z0-9_]+)=.*/\1/p'\'' | LC_ALL=C /usr/bin/sort -u)' \
    'EOF' \
    '' \
    '  PUBLISH_ROOT="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/gotosleep-publish.XXXXXX")"' \
    '  PUBLISH_HOME="$PUBLISH_ROOT/home"' \
    '  PUBLISH_TEMPLATE="$PUBLISH_ROOT/template"' \
    '  PUBLISH_REPOSITORY="$PUBLISH_ROOT/repository.git"' \
    '  /bin/mkdir -p "$PUBLISH_HOME" "$PUBLISH_TEMPLATE"' \
    '' \
    '  publish_git() {' \
    '    /usr/bin/env \' \
    '      HOME="$PUBLISH_HOME" \' \
    '      XDG_CONFIG_HOME="$PUBLISH_HOME/.config" \' \
    '      GIT_CONFIG_NOSYSTEM=1 \' \
    '      GIT_CONFIG_SYSTEM=/dev/null \' \
    '      GIT_CONFIG_GLOBAL=/dev/null \' \
    '      GIT_CONFIG_COUNT=0 \' \
    '      GIT_ATTR_NOSYSTEM=1 \' \
    '      GIT_NO_REPLACE_OBJECTS=1 \' \
    '      GIT_TEMPLATE_DIR="$PUBLISH_TEMPLATE" \' \
    '      GIT_TERMINAL_PROMPT=0 \' \
    '      /usr/bin/git -c core.hooksPath=/dev/null "$@"' \
    '  }' \
    '' \
    '  publish_git init --quiet --bare --template="$PUBLISH_TEMPLATE" \' \
    '    "$PUBLISH_REPOSITORY"' \
    '  publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '    fetch --quiet --no-tags --no-write-fetch-head "$SOURCE_REPOSITORY" \' \
    '      refs/heads/main:refs/heads/main \' \
    '      "$RELEASE_TAG_REF:$RELEASE_TAG_REF"' \
    '' \
    '  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '      rev-parse refs/heads/main^{commit})" = "$EXPECTED_MAIN_OBJECT" ]' \
    '  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '      cat-file -t "$RELEASE_TAG_REF")" = tag ]' \
    '  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '      rev-parse "$RELEASE_TAG_REF^{object}")" = "$EXPECTED_TAG_OBJECT" ]' \
    '  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '      rev-parse "$RELEASE_TAG_REF^{commit}")" = "$EXPECTED_MAIN_OBJECT" ]' \
    '  EXPECTED_REFS="$(printf '\''%s\n'\'' \' \
    '    refs/heads/main "$RELEASE_TAG_REF" | LC_ALL=C /usr/bin/sort)"' \
    '  ACTUAL_REFS="$(publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '    for-each-ref --format='\''%(refname)'\'' | LC_ALL=C /usr/bin/sort)"' \
    '  [ "$ACTUAL_REFS" = "$EXPECTED_REFS" ]' \
    '' \
    '  publish_git --git-dir="$PUBLISH_REPOSITORY" \' \
    '    -c remote.origin.mirror=false \' \
    '    -c push.followTags=false \' \
    '    -c push.gpgSign=false \' \
    '    push --no-follow-tags --no-signed --no-mirror "$PUBLIC_REMOTE_URL" \' \
    '      refs/heads/main:refs/heads/main \' \
    '      "$RELEASE_TAG_REF:$RELEASE_TAG_REF"' \
    '' \
    '  cleanup_publish' \
    '  PUBLISH_ROOT=""' \
    '  trap - EXIT HUP INT TERM' \
    ')'
fi
