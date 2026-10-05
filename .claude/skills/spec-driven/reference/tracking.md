# Estimates, actuals, and calibration

One rule sits behind all of this: an agent cannot know how long a human takes. Nothing here is
inferred from session runtime, token counts, or commit timestamps. Estimates are honest guesses
that get calibrated against recorded actuals.

## The unit

**Person-days, for one person working with AI.** Not calendar days. Not hours.

## The record

Every RFC's `tasks.md` opens with this block, and it is authoritative for that RFC.

```markdown
## Progress

| Field | Value |
| --- | --- |
| Estimate | 3 person-days — same shape as 0002, which took 4 against a guess of 2 (2026-10-01) |
| Started | 2026-10-01 |
| Implementation accepted | — |
| Completed | — |
| Days worked | 2026-10-01, 2026-10-03 |
```

- **Estimate** — the guess in person-days, the date, and what it was calibrated against. To
  revise, replace the line and keep the old one beneath it, so the history of the guess survives.
- **Started** — the date real work began.
- **Implementation accepted** — the human approver, absolute date, and exact implementation commit.
  Fill it only after implementation review and corrections; the later current-spec fold does not
  rewrite this identity.
- **Completed** — the date completion actually held. If the RFC is later reopened, keep the first
  completion date.
- **Days worked** — the dates on which anyone worked this RFC. Person-days is the length of the
  list.

## Counting a day worked

Any work on an RFC on a given calendar date counts that date, once. Ten minutes and eight hours
both count one day. This is deliberately coarse — across several RFCs it washes out, and it is the
only unit an agent can record honestly.

`python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day NNNN` sets
`Started` if it is still blank and adds today only if missing, so running it every session is correct.
Use it instead of editing the row by hand. It leaves the change uncommitted, because the day stamp
belongs in the commit with the work it describes.

## Calibrating the next estimate

Before writing any estimate, read the actuals of comparable finished work. Recording actuals is
only worth doing if this step happens.

1. Find completed RFCs of similar size and kind under `specs/changes/archive/*/tasks.md`.
   `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" status`
   prints every RFC with its estimate, dates, and day count.
2. Compare each one's estimate against its days worked. Note the direction and size of the miss.
3. Estimate the new RFC by analogy to a specific finished one, and name it: "same shape as
   `0002`, which took 4 against a guess of 2, so 4."

Always state the comparison. If nothing comparable exists yet, say so — the first estimates in a new
project are uncalibrated by definition, and labelling them that way is more useful than implying
confidence.

## What not to do

Do not turn elapsed calendar time into person-days; elapsed time includes nights, weekends, and
waiting on review. Do not backfill a plausible date from today's clock; an unknown date stays
`unknown`.
