# Plan tasks

Requires an `Approved` RFC. Before writing the list, read the RFC, the current specs, the notes, and the
actual code and tests.

Break the RFC into tasks in `tasks.md`:

- Give every task a stable ID (`T1`, `T2`, …), a checkable **done when**, and an **after** list naming
  the tasks it depends on. A task with no dependencies says `after: —`.
- Every task traces to the RFC. A task that introduces behavior the RFC does not specify means the RFC is
  incomplete, so fix the RFC.
- Size tasks so each one ends at a stable, resumable point, where the tree builds and the checks that
  exist so far pass. That is what lets the work be interrupted at any task boundary and resumed by a
  different session.
- Cover the unglamorous work explicitly: tests for each acceptance criterion, failure paths, migration
  of existing data, and security-sensitive work where it applies.
- Order the list so every task appears after everything in its `after` list. When two tasks are
  independent, say so; that is where work can be reordered or parallelized.
- Keep the validation, acceptance, and completion sections from the template. They are part of the
  plan.

A planned command is not evidence.

Set the phase to `Implementing` when the first implementation task starts, and continue with
`procedures/implement-rfc.md`.

## Estimates

Record the person-day estimate for the RFC in the Progress block, calibrated against comparable
finished work (`reference/tracking.md`). Estimate the RFC, not each task — task-level person-days
are false precision at this granularity. If planning changes the estimate materially, revise the
line and say why.
