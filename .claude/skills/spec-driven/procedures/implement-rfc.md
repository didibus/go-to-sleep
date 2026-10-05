# Implement, resume, or fix an RFC

## Before starting

1. Confirm the RFC is `Approved` or `Implementing`, and that the approved revision is the one in front
   of you. Read the current specs, `tasks.md`, `notes.md`, and the real code and tests.
2. When resuming, start from the Resume context in `notes.md`. Check it against the working tree. If it
   disagrees with what is actually there, trust the tree, and fix the notes before going further.
3. Run
   `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day <NNNN>`.

## The task loop

Repeat until every implementation task is done:

1. Take the first task whose `after` list is complete. Mark it in progress in `tasks.md`.
2. Make the smallest safe change that completes it. Prefer tests before or alongside code. Preserve
   unrelated behavior and existing architecture.
3. Run the applicable checks. Record the exact commands, the commit or dirty working state they ran
   against, and what they returned. Never weaken a test to get a pass. A required check that failed or
   could not run blocks the task.
4. Tick the task. Update `notes.md` *now*, not at the end:
   - discoveries;
   - decisions made inside the approved design;
   - the commands and evidence;
   - a rewritten Resume context: what is done, what is in progress, what is blocked, and the exact next
     action.

   A different session must be able to continue from the notes alone.
5. If behavior has to diverge from the approved design, stop. Record why in `notes.md`, return the RFC
   to `In Review` through `procedures/create-rfc.md`, and continue only after reapproval.

Commit at task boundaries only if the human has said to. Otherwise leave the work in the tree; the
notes say what is there.

## Verify

When every implementation task is ticked, run every check the RFC requires against the implementation
candidate. Validate each acceptance criterion with the evidence the RFC names, and record the results in
`notes.md`. Then set the phase to `Verified`. `specs/current/` is still untouched at this point.

## Human validation and acceptance

1. Hand the implementation to the human:
   - what changed;
   - how to try it;
   - each acceptance criterion with its evidence;
   - anything they should look at especially hard, such as a decision made inside the design, a known
     limitation, or a check that could not run.
2. Apply requested corrections as ordinary tasks in `tasks.md`, back in `Implementing`. Rerun the
   affected checks and return to `Verified`. Repeat until the human accepts.
3. With the human's go-ahead, commit the implementation so there is an exact commit to accept. Record the
   approver, date, and commit in the Progress block's `Implementation accepted` row, and set the phase to
   `Accepted`.
4. Continue with `procedures/update-current-specs.md`, then `procedures/archive-change.md`.

## Fixing a regression

For a defect found after an RFC is archived, open a new RFC; do not reopen the archive. A small fix
still goes through the loop, lightly: a one-round intent check, a short RFC whose acceptance criteria
include a regression test, then implementation. Keep the original RFC's acceptance record and dates
intact.
