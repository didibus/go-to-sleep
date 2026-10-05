# Bootstrap current specs

Use this when there is code but `specs/current/` is absent, incomplete, or demonstrably stale. The goal is
a factual baseline to change from, not a complete description of everything.

For a new project with no code yet, there is nothing to bootstrap. The first completed RFC's fold
creates `specs/current/` (`procedures/update-current-specs.md`).

1. Identify the baseline, normally the latest commit on the main branch. Do not document a half-finished
   branch as current behavior.
2. Inspect the architecture, boundaries, interfaces, domain state, persistence, tests, and operations.
   Where docs and code disagree, read the implementation to settle it — the code wins.
3. Write concise Markdown for the behavior that matters, with pointers to the code and tests that
   evidence it, and explicit markers on what you could not confirm. Do not fill a gap with what the
   system probably does or should do.
4. Keep anything aspirational in an RFC. Record the contradictions you found, and ask about the ones
   that affect the change you are about to make.
5. Review it with the human. Run existing checks where useful and authorized. Mark unverified legacy
   areas as unverified rather than implying you proved them.

Done means a usable factual baseline exists — not that every behavior has been re-proven by new
tests.
