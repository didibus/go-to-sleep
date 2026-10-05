# Complete and archive a change

Requires all of the following:

- the code implemented;
- every acceptance criterion validated with evidence;
- all required checks passing;
- the human's acceptance of the exact implementation commit;
- that accepted behavior folded into `specs/current/` and committed with the code.

A documented failure or a check that could not run does not satisfy any of these.

1. Record the completion date now, against fresh evidence — not the date you hoped to finish. Keep the
   original start date, the first completion date if this RFC was reopened, and the estimate history.
   The archived Progress block is what future estimates calibrate against (`reference/tracking.md`),
   so make its estimate and days-worked lines accurate now; this is the one chance.
2. Set the RFC phase to `Complete`, and finish `notes.md`'s Resume context with a closing line: what
   shipped, and the accepted and integrated commits.
3. Move `specs/changes/active/NNNN-slug/` to `specs/changes/archive/NNNN-slug/` with `RFC.md`,
   `tasks.md`, and `notes.md` intact. Leave `last-number.md` alone — archiving never frees or reuses a
   number. Never delete tests that guard against regressions.
4. With the human's go-ahead, commit the archive move. It may be a documentation-only follow-up to the
   code-and-specs commit. Keep the accepted implementation commit and the integrated commit recorded
   separately from it.

For rejected or superseded work, record that phase and the reason instead of filing it as delivered.
Name the successor for a superseded RFC. Then archive it the same way. Nothing is deleted, so the number
stays used and the reasoning survives.
