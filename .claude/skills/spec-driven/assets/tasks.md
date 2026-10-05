# Tasks — RFC NNNN

## Progress

| Field | Value |
| --- | --- |
| Estimate | N person-days — basis for the number (YYYY-MM-DD) |
| Started | YYYY-MM-DD |
| Implementation accepted | — |
| Completed | — |
| Days worked | YYYY-MM-DD |

Run
`python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day NNNN`
when you begin working: it sets *Started* if it is still blank and adds today to *Days worked* only
if missing. See the skill's `reference/tracking.md`.

## Design

- [ ] Interview the human and get the intent brief confirmed.
- [ ] Draft the RFC, resolve its open design questions, and get human approval of the revision.

## Implementation

Every task has an ID, an `after` list, and a checkable done condition, and traces to the RFC. A task
appears only after everything it depends on.

- [ ] T1 — Tests for the acceptance criteria and the failure paths. after: — · done when: …
- [ ] T2 — Implement the approved behavior. after: T1 · done when: …
- [ ] T3 — Run every required check and record the commands and output; validate each acceptance
      criterion; set the phase to Verified. after: T2 · done when: every criterion has evidence in
      `notes.md`.

## Validation and acceptance

- [ ] Hand the implementation to the human, with each acceptance criterion and its evidence.
- [ ] Apply requested corrections as new tasks above, and rerun the affected checks.
- [ ] Record the approver, date, and exact implementation commit in the Progress table; set the phase to
      Accepted.

## Current-spec fold and completion

- [ ] Fold the accepted implementation into `specs/current/`, without adding behavior.
- [ ] Commit code and current specs together; confirm the result matches the accepted commit.
- [ ] Record the completion date, set the phase to Complete, and archive.
