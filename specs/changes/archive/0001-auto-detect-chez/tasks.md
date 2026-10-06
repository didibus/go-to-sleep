# Tasks — RFC 0001

## Progress

| Field | Value |
| --- | --- |
| Estimate | 0.5 person-day — focused shell discovery, regression coverage, documentation, and package verification (2026-10-06) |
| Started | 2026-10-06 |
| Implementation accepted | didibus (repository owner), 2026-10-06 — `0b827a90869c498ac74397605af2c66b47ed0dc8` |
| Completed | 2026-10-06 |
| Days worked | 2026-10-06 |

Run
`python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day 0001`
when you begin working: it sets *Started* if it is still blank and adds today to *Days worked* only
if missing. See the skill's `reference/tracking.md`.

## Design

- [x] Interview the human and get the intent brief confirmed.
- [x] Draft the RFC, resolve its open design questions, and get human approval of revision
      `e29846741b910d8daca308cc48c01b2f6d7c03ef`.

## Implementation

Every task has an ID, an `after` list, and a checkable done condition, and traces to the RFC. A task
appears only after everything it depends on.

- [x] T1 — Add focused discovery, explicit-override, missing-toolchain, incomplete-directory, and
      wrong-version regression coverage. after: — · done when: each acceptance path has a test that
      fails against the imported baseline for the intended reason.
- [x] T2 — Implement toolchain discovery and update public build documentation. after: T1 · done
      when: routine builds need no environment exports, explicit overrides remain supported, failure
      is pre-mutation and actionable, and focused tests pass.
- [x] T3 — Run every required check and record the commands and output; validate each acceptance
      criterion; set the phase to Verified. after: T2 · done when: every criterion has evidence in
      `notes.md`.

## Validation and acceptance

- [x] Hand the implementation to the human, with each acceptance criterion and its evidence.
- [x] Apply requested corrections as new tasks above, and rerun the affected checks. No corrections
      were requested.
- [x] Record the approver, date, and exact implementation commit in the Progress table; set the phase to
      Accepted.

## Current-spec fold and completion

- [x] Fold the accepted implementation into `specs/current/`, without adding behavior.
- [x] Commit code and current specs together; confirm the result matches the accepted commit.
- [x] Record the completion date, set the phase to Complete, and archive.
