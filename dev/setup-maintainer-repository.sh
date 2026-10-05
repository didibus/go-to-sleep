#!/bin/bash
# Maintainer-only setup for the repository owner's local commit identity.
set -euo pipefail

readonly SCRIPT_PATH='dev/setup-maintainer-repository.sh'
readonly PROJECT_ID_PATH='specs/project-id.txt'
readonly VERSION_PATH='src/gotosleep/version.clj'
readonly EXPECTED_PROJECT_ID='go-to-sleep'
readonly MAINTAINER_NAME='didibus'
readonly MAINTAINER_EMAIL='601540+didibus@users.noreply.github.com'

fail()
{
  printf 'setup-maintainer-repository: %s\n' "$*" >&2
  exit 1
}

command -v git >/dev/null 2>&1 ||
  fail 'git is required'

inside_worktree="$(git rev-parse --is-inside-work-tree 2>/dev/null || true)"
[ "$inside_worktree" = true ] ||
  fail 'run this command from inside the Go To Sleep worktree'

bare_repository="$(git rev-parse --is-bare-repository 2>/dev/null || true)"
[ "$bare_repository" = false ] ||
  fail 'bare repositories are not supported'

repository_root_logical="$(git rev-parse --show-toplevel 2>/dev/null)" ||
  fail 'cannot locate the repository worktree'
repository_root="$(cd "$repository_root_logical" 2>/dev/null && pwd -P)" ||
  fail 'cannot resolve the repository worktree'
current_directory="$(pwd -P)"

case "$current_directory" in
  "$repository_root"|"$repository_root"/*) ;;
  *) fail 'current directory is outside the repository worktree' ;;
esac

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd -P)" ||
  fail 'cannot resolve the setup command path'
actual_script="$script_directory/$(basename "${BASH_SOURCE[0]}")"
expected_script="$repository_root/$SCRIPT_PATH"
[ "$actual_script" = "$expected_script" ] ||
  fail 'this command belongs to a different repository'

verify_tracked_regular()
{
  relative_path="$1"
  expected_mode="$2"
  absolute_path="$repository_root/$relative_path"

  git -C "$repository_root" ls-files --error-unmatch -- "$relative_path" \
    >/dev/null 2>&1 ||
    fail "required tracked path is missing: $relative_path"

  tracked_entry="$(git -C "$repository_root" ls-files --stage -- "$relative_path")"
  tracked_entry_count="$(printf '%s\n' "$tracked_entry" | wc -l | tr -d '[:space:]')"
  actual_mode="$(printf '%s\n' "$tracked_entry" | awk 'NR == 1 { print $1 }')"
  actual_stage="$(printf '%s\n' "$tracked_entry" | awk 'NR == 1 { print $3 }')"
  [ "$tracked_entry_count" = 1 ] && [ "$actual_stage" = 0 ] ||
    fail "required tracked path is unmerged: $relative_path"
  [ "$actual_mode" = "$expected_mode" ] ||
    fail "required tracked path has the wrong mode: $relative_path"
  [ -f "$absolute_path" ] && [ ! -L "$absolute_path" ] ||
    fail "required worktree path is not a regular file: $relative_path"
}

verify_tracked_regular "$SCRIPT_PATH" 100755
verify_tracked_regular "$PROJECT_ID_PATH" 100644
verify_tracked_regular "$VERSION_PATH" 100644

project_id_file="$repository_root/$PROJECT_ID_PATH"
project_id_size="$(
  LC_ALL=C wc -c < "$project_id_file" |
    tr -d '[:space:]'
)"
[ "$project_id_size" = 12 ] &&
  [ "$(cat "$project_id_file")" = "$EXPECTED_PROJECT_ID" ] ||
  fail "unexpected project identity in $PROJECT_ID_PATH"

git -C "$repository_root" config --local --no-includes --replace-all \
  user.name "$MAINTAINER_NAME"
git -C "$repository_root" config --local --no-includes --replace-all \
  user.email "$MAINTAINER_EMAIL"

name_count="$(
  git -C "$repository_root" config --local --no-includes --get-all user.name |
    wc -l |
    tr -d '[:space:]'
)"
email_count="$(
  git -C "$repository_root" config --local --no-includes --get-all user.email |
    wc -l |
    tr -d '[:space:]'
)"
[ "$name_count" = 1 ] &&
  [ "$(
    git -C "$repository_root" config --local --no-includes --get user.name
  )" = "$MAINTAINER_NAME" ] ||
  fail 'could not set exactly one repository-local user.name'
[ "$email_count" = 1 ] &&
  [ "$(
    git -C "$repository_root" config --local --no-includes --get user.email
  )" = "$MAINTAINER_EMAIL" ] ||
  fail 'could not set exactly one repository-local user.email'

printf 'user.name=%s\n' "$MAINTAINER_NAME"
printf 'user.email=%s\n' "$MAINTAINER_EMAIL"
