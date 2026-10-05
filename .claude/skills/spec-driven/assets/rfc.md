# RFC NNNN — Change title

| Field | Value |
| --- | --- |
| ID | package-slug/NNNN |
| Phase | Draft |
| Approved by | who approved the design, and the date — blank until approved |
| Approved revision | commit of the revision that was approved — blank until approved |

Progress, estimates, day counts, and implementation acceptance live in `tasks.md`, not here.

## Intent

The intent brief the human confirmed (`procedures/declare-intent.md`): outcome, motivation, success
signals, and constraints. Record it as confirmed; do not embellish it.

## Scope and non-goals

## Current behavior

What `specs/current/` says today, and where it is silent or wrong.

## Proposed behavior

Specify rather than describe: concrete signatures, data shapes, field names, enums, error shapes, file
paths. Cover the affected interfaces, the invariants, the state this change owns, errors, security, and
edge cases. Use RFC 2119 keywords for anything testable.

The body states what is proposed. Rationale, alternatives explored, and research go in the appendix.
Cut anything redundant, superfluous, or obvious, and do not describe the document's own structure.
Size each section to its importance.

## Compatibility and migration

What happens to existing data, configuration, and behavior. Include how to roll back where it applies.

## Acceptance criteria

Stable IDs (`AC1`, `AC2`, …) and observable expected results. Name the check for each — the test, the
command, or the manual step and what it should show. Every success signal in the Intent section maps to
at least one criterion.

An acceptance criterion that asserts something weaker than the requirement it came from is the most
common defect in this document.

## Current-spec changes

The `specs/current/` files whose implemented behavior this change will alter or create. Name them during
design, but do not edit them before the implementation is accepted. After acceptance, derive these edits
from the accepted commit and commit them with the code.

## Open questions

Each one: what is undecided, who decides, and what is blocked until they do.

## Appendix — rationale and alternatives
