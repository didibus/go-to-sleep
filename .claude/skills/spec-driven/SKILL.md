---
name: spec-driven
description: Drive changes to this codebase through the RFC loop. The loop is declare intent (with a thorough interview), draft an RFC, get human approval, plan dependency-linked tasks, implement while keeping resumable notes, get human validation, update the current spec, and archive. Use this whenever work starts from an idea, a bug, a feature request, or "here is what I want to change". Also use it whenever asked to draft, refine, approve, implement, resume, fix, finish, or archive an RFC, or to find out what the system currently does.
metadata:
  version: "5.0.0"
---

# Spec-driven development

One RFC at a time, always leaving behind an accurate `specs/current/`.

```text
intent            -> interview the human until the intent is clear -> they confirm the brief
  -> RFC draft    -> human reviews and approves
  -> tasks        -> dependency-linked, each with a done condition
  -> implement    -> notes kept at every task boundary, so any session can resume
  -> checks pass  -> human validates and accepts
  -> specs/current/ updated from the accepted code -> RFC marked Complete and archived
```

`specs/current/` is the promise this loop keeps. At any moment it describes what the system actually
does, because the RFC that changed the behavior also updated it.

## Every session, before anything else

1. Read the repository's own guidance (`CLAUDE.md`, and `specs/project.md` if it exists). Preserve the
   conventions already there rather than imposing this skill's defaults.
2. Establish where you are: which RFC, its phase, the branch, and what is uncommitted. Run
   `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" status` for
   the list of RFCs and their phases.
3. Read the actual material — the relevant `specs/current/`, the RFC, `tasks.md`, `notes.md`, and
   the code and tests you are about to touch. Not last session's summary of them.
4. When resuming, start from the RFC's `notes.md` Resume context and `tasks.md`. Confirm what they
   claim against the working tree before continuing.
5. If you will work on an existing RFC today, run
   `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day <NNNN>`
   to stamp today into its Progress block. It is idempotent (`reference/tracking.md`).
6. Pick the one procedure that matches the request. A small change does not need every artifact.

## Load what the task needs

| Task | Read |
| --- | --- |
| The rules that hold all of this together | `reference/protocol.md` |
| Paths, numbering, and the write guard | `reference/repository-layout.md` |
| Numbers, day stamps, status, checks | `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" --help` |
| Estimates, days worked, calibration | `reference/tracking.md` |
| Understand what the human wants before drafting | `procedures/declare-intent.md` |
| Draft or revise an RFC and get it approved | `procedures/create-rfc.md` |
| Turn an approved RFC into tasks | `procedures/plan-tasks.md` |
| Implement, resume, or fix | `procedures/implement-rfc.md` |
| Fold what you built into `specs/current/` | `procedures/update-current-specs.md` |
| Complete and archive a finished change | `procedures/archive-change.md` |
| Write `specs/current/` for code that has none | `procedures/bootstrap-current-specs.md` |
| Templates | `assets/rfc.md`, `assets/tasks.md`, `assets/notes.md`, `assets/project.md` |

Documentation paths above are relative to this skill's directory. Invoke `rfc.py` through the
repository-root command shown above; it works from any directory inside the repository and finds
`specs/changes/` itself.

## How to work

**Interview before you draft.** When the human declares an intent, find out what they want, what it is
for, and how they will judge it. Keep asking until you could write acceptance criteria they would sign.
Play the understanding back and get it confirmed (`procedures/declare-intent.md`). Label harmless
assumptions and keep going. Never settle a genuine product decision by inventing intent; that is the one
thing you cannot do on the human's behalf.

**Design approval is a human act at a specific revision.** Get it before implementing. If the design
changes materially afterwards, go back for it. An agent incrementing a revision has approved nothing.

**Tasks implement the approved RFC**, they do not add requirements. If implementation reveals the
design is wrong, stop. Revise the RFC and get it reapproved rather than quietly building something else.

**Notes are for whoever comes next** — usually a different session with none of your context. Update
`notes.md` at every task boundary, not at the end. Write what you learned, what you ran, what you decided,
and exactly what you would do next.

**Failing or unavailable checks block.** Reporting a failure is not passing it.

**Implementation acceptance is a separate human act at an exact commit.** It comes after implementation,
checks, and correction rounds. Keep `specs/current/` unchanged until that acceptance. Then derive the
current-spec update from the accepted code, and commit the two together. If the fold exposes a mismatch,
reopen implementation review.

**Commit only with the human's go-ahead.** Local edits are free. Commits and merges follow the
repository's own rules and the human's say-so.

## Ending a session

Say what the RFC is and where it stands, what you actually changed, and what you actually ran and what
it said. Say what is unresolved and what the next concrete action is. Make sure `notes.md` says the same.
Do not imply that anything continues after you stop.
