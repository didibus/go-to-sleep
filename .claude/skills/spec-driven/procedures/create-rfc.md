# Create or revise an RFC

Requires a confirmed intent brief (`procedures/declare-intent.md`). If the human asks for a draft
without one, run the interview first; for a small change it can be a single round.

## Draft it

1. Read the relevant `specs/current/` and the actual code first.
2. If an RFC already exists for this intent, refine it. Do not create a second one.
3. For a new change, run
   `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" new "<slug>"`.
   It allocates the next number, records it in `last-number.md`, and scaffolds
   `specs/changes/active/NNNN-slug/` with `RFC.md`, `tasks.md`, and `notes.md`. Do not hand-pick a
   number (`reference/repository-layout.md`).
4. Write the delta using the scaffolded `RFC.md`. Start with the confirmed intent brief in the Intent
   section. Specify rather than describe: real signatures, data shapes, field names, enums, error
   shapes, file paths. Every acceptance criterion must trace back to a success signal in the brief. If a
   signal has no criterion, the RFC is incomplete.
5. Estimate in person-days, calibrated against comparable finished RFCs, and record it in the
   Progress block (`reference/tracking.md`).

## Get it right before getting it approved

6. Investigate anything genuinely ambiguous instead of guessing. New ambiguity about *intent* goes back
   to the human as a question; ambiguity about *design* you resolve by reading and trying things.
7. For risky persistence, security, migration, or system-level work, run separate critique passes:
   architecture, implementation, and testing. Use real subagents if the harness has them, or clearly
   labelled sequential passes if it does not. Never invent an agent that did not run.

   Aim the critiques at the defects this workflow actually misses:
   - acceptance criteria that assert something weaker than the requirement above them;
   - behavior whose required state no component owns;
   - lifecycles with no termination condition;
   - failure paths nobody specified.

8. Reconcile the findings into the one canonical RFC. Record real alternatives in the appendix. List the
   decisions that are still the human's to make under Open questions.

## Approve

9. Set the phase to `In Review` and walk the human through the RFC: what it does, the decisions it
   makes, the open questions, and how each acceptance criterion will be checked. Ask focused questions
   and revise until they are satisfied.
10. Get approval for the exact revision. With the human's go-ahead, commit the RFC so the reviewed
    revision is an identifiable commit. Record the approver, the date, and that commit in the RFC's
    metadata. An agent's summary is not approval evidence. Then set the phase to `Approved` and continue
    with `procedures/plan-tasks.md`.

## When a revision changes something material

Material means external behavior, data format, compatibility, scope, or acceptance criteria. Pause the
affected implementation, return the RFC to `In Review`, revise it, and get approval again before
continuing. Local detail inside the already-approved design stays local and needs none of that — record
it in `notes.md`.
