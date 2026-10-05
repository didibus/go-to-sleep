# Update the current specs

This is what makes the loop worth running: there is always an accurate spec of what the system does
today, because the RFC that changed it also updated it.

Start only after the human has accepted the implementation at an exact commit (phase `Accepted`).
`specs/current/` stays unchanged during implementation and implementation review, because it describes
accepted current behavior, not a candidate.

Read the current specs, the approved RFC and its Current-spec changes section, the accepted
implementation commit, and its evidence. Fold in **accepted implemented behavior only**:

- delete what is now contradicted;
- keep compatibility and migration facts that still hold;
- keep changes from other RFCs;
- point at the code and tests that evidence each behavior;
- leave the RFC's rationale in the RFC. A current spec says what is, not why it was chosen.

If `specs/current/` does not exist yet, as in a new project, this fold creates it. Write only the
sections the accepted behavior needs (`procedures/bootstrap-current-specs.md` describes the shape).

The fold is a derivation from the accepted implementation, not another chance to change it. If the
current-spec work reveals missing behavior, an inconsistency, or a material semantic choice, stop and
reopen implementation review. Do not silently change code after acceptance, and do not make the current
spec describe behavior the accepted commit does not have.

With the human's go-ahead, commit the current-spec fold together with the accepted code, in the same
commit or the same merge. Then verify that the committed code still matches the accepted commit. If it
does not, the acceptance is stale: rerun the affected checks and get fresh acceptance before completing.

Continue with `procedures/archive-change.md`.
