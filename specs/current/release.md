# Public release workflow

## Canonical repository

The public GitHub repository is the canonical source for development after the 0.1.0 publication
cutover. Its root commit is an imported, accepted 0.1.0 baseline. The development history used before
that public baseline was intentionally not published.

The root commit and published `v0.1.0` tag are immutable. Later work continues through ordinary
branches, reviewed commits on `main`, and new immutable annotated version tags. No later history is
grafted onto an unpublished predecessor repository.

## Future release preparation

Every release after 0.1.0 is prepared through an RFC. The change updates:

- `src/gotosleep/version.clj`;
- `CHANGELOG.md` in Keep a Changelog form;
- release-facing version and artifact examples in `README.md`;
- the relevant documents under `specs/current/`.

On clean `main`, create the proposed annotated version tag and run:

```sh
bash "$(git rev-parse --show-toplevel)/dev/check-release.sh" \
  vMAJOR.MINOR.PATCH YYYY-MM-DD
```

The checker requires the tag, production version, changelog, README examples, and release spec to
agree. It tests an isolated checkout of the exact tag, builds and audits the package, runs generic
provenance checks, and creates a tag-bound handoff containing exactly the package and `SHA256SUMS`.
It does not create, move, delete, or push a tag and does not upload assets.

Run the exact owner command printed by the checker from the same verified `main` checkout. It embeds
the audited repository URL and the expected `main` and annotated-tag object IDs. The command does
not publish from the working repository or use its local Git configuration. Instead, it scrubs Git
environment and system/global configuration, fetches only the two audited refs into a new temporary
bare repository with an empty template, verifies both embedded object IDs, and pushes only those
exact refs with hooks, follow-tags, push signing, and mirroring disabled:

```sh
(
  set -euo pipefail
  PUBLIC_REMOTE_URL='https://github.com/OWNER/REPOSITORY.git'
  EXPECTED_MAIN_OBJECT='AUDITED_MAIN_COMMIT_OBJECT_ID'
  EXPECTED_TAG_OBJECT='AUDITED_ANNOTATED_TAG_OBJECT_ID'
  RELEASE_TAG_REF='refs/tags/vMAJOR.MINOR.PATCH'
  SOURCE_REPOSITORY="$(pwd -P)"
  PUBLISH_ROOT=""

  cleanup_publish() {
    if [ -n "$PUBLISH_ROOT" ] && [ -d "$PUBLISH_ROOT" ]; then
      /bin/rm -rf -- "$PUBLISH_ROOT"
    fi
  }

  publish_exit() {
    PUBLISH_STATUS=$?
    trap - EXIT HUP INT TERM
    cleanup_publish
    exit "$PUBLISH_STATUS"
  }

  publish_signal() {
    PUBLISH_STATUS="$1"
    trap - EXIT HUP INT TERM
    cleanup_publish
    exit "$PUBLISH_STATUS"
  }

  trap publish_exit EXIT
  trap 'publish_signal 129' HUP
  trap 'publish_signal 130' INT
  trap 'publish_signal 143' TERM

  while IFS= read -r GIT_ENV_NAME; do
    [ -n "$GIT_ENV_NAME" ] && unset "$GIT_ENV_NAME"
  done <<EOF
$(/usr/bin/env | /usr/bin/sed -nE 's/^(GIT_[A-Za-z0-9_]+)=.*/\1/p' | LC_ALL=C /usr/bin/sort -u)
EOF

  PUBLISH_ROOT="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/gotosleep-publish.XXXXXX")"
  PUBLISH_HOME="$PUBLISH_ROOT/home"
  PUBLISH_TEMPLATE="$PUBLISH_ROOT/template"
  PUBLISH_REPOSITORY="$PUBLISH_ROOT/repository.git"
  /bin/mkdir -p "$PUBLISH_HOME" "$PUBLISH_TEMPLATE"

  publish_git() {
    /usr/bin/env \
      HOME="$PUBLISH_HOME" \
      XDG_CONFIG_HOME="$PUBLISH_HOME/.config" \
      GIT_CONFIG_NOSYSTEM=1 \
      GIT_CONFIG_SYSTEM=/dev/null \
      GIT_CONFIG_GLOBAL=/dev/null \
      GIT_CONFIG_COUNT=0 \
      GIT_ATTR_NOSYSTEM=1 \
      GIT_NO_REPLACE_OBJECTS=1 \
      GIT_TEMPLATE_DIR="$PUBLISH_TEMPLATE" \
      GIT_TERMINAL_PROMPT=0 \
      /usr/bin/git -c core.hooksPath=/dev/null "$@"
  }

  publish_git init --quiet --bare --template="$PUBLISH_TEMPLATE" \
    "$PUBLISH_REPOSITORY"
  publish_git --git-dir="$PUBLISH_REPOSITORY" \
    fetch --quiet --no-tags --no-write-fetch-head "$SOURCE_REPOSITORY" \
      refs/heads/main:refs/heads/main \
      "$RELEASE_TAG_REF:$RELEASE_TAG_REF"

  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \
      rev-parse refs/heads/main^{commit})" = "$EXPECTED_MAIN_OBJECT" ]
  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \
      cat-file -t "$RELEASE_TAG_REF")" = tag ]
  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \
      rev-parse "$RELEASE_TAG_REF^{object}")" = "$EXPECTED_TAG_OBJECT" ]
  [ "$(publish_git --git-dir="$PUBLISH_REPOSITORY" \
      rev-parse "$RELEASE_TAG_REF^{commit}")" = "$EXPECTED_MAIN_OBJECT" ]
  EXPECTED_REFS="$(printf '%s\n' \
    refs/heads/main "$RELEASE_TAG_REF" | LC_ALL=C /usr/bin/sort)"
  ACTUAL_REFS="$(publish_git --git-dir="$PUBLISH_REPOSITORY" \
    for-each-ref --format='%(refname)' | LC_ALL=C /usr/bin/sort)"
  [ "$ACTUAL_REFS" = "$EXPECTED_REFS" ]

  publish_git --git-dir="$PUBLISH_REPOSITORY" \
    -c remote.origin.mirror=false \
    -c push.followTags=false \
    -c push.gpgSign=false \
    push --no-follow-tags --no-signed --no-mirror "$PUBLIC_REMOTE_URL" \
      refs/heads/main:refs/heads/main \
      "$RELEASE_TAG_REF:$RELEASE_TAG_REF"

  cleanup_publish
  PUBLISH_ROOT=""
  trap - EXIT HUP INT TERM
)
```

The placeholders above explain the printed command; use the checker's embedded values rather than
typing them manually. Never publish with `--all`, `--tags`, or `--mirror`. Attach only the exact
audited handoff pair to the matching GitHub Release. Published tags never move or get replaced.

## Release 0.1.0

The 0.1.0 release consists of:

- release date `2026-10-05`;
- annotated tag `v0.1.0`;
- `GoToSleep-0.1.0.pkg`;
- `SHA256SUMS`.

The accepted package SHA-256 is:

```text
d40ab7fb38f2b8334237e030ffc1f6f1eba8e20fdbb71cb37401c95256c64dfe
```

The accepted package predates the public repository baseline and is not reproducible byte-for-byte.
The normal handoff command therefore refuses `v0.1.0`; it must not create a new uploadable 0.1.0
package. To exercise the current source and package checks against that imported baseline, use:

```sh
bash "$(git rev-parse --show-toplevel)/dev/check-release.sh" \
  --verification-only v0.1.0 2026-10-05
```

Verification-only mode produces `GoToSleep-0.1.0.rebuilt.pkg` and `SHA256SUMS.rebuilt` as
non-uploadable structural evidence. Those files may differ from the accepted package, are not a
release handoff, and must never be attached to a GitHub Release.

The package is Apple-silicon-only, ad-hoc payload signed, and neither Developer ID signed nor
notarized. Checksums verify bytes but do not authenticate publisher identity.

The complete installed reboot/login enforcement sequence remains deferred and was not passed.
