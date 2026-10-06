# Notes — RFC 0001

Written for whoever picks this up next, who has none of your context. Update it at every task
boundary.

## Resume context

- **Phase and revision** — In Review; the RFC has not yet been approved.
- **Branch** — `rfc/0001-auto-detect-chez`; dirty only with the RFC scaffold and draft.
- **Done** — confirmed the intent and drafted the RFC and task plan.
- **In progress** — design review.
- **Blocked** — implementation is blocked on repository-owner approval of the exact RFC revision.
- **Next action** — obtain design approval, commit the approved RFC, then begin T1.

## Discoveries and uncertainty

- Jolt 0.8.16 successfully performs a signable build without either override when a normal Chez
  Scheme 10.4.1 installation is available through `PATH`.
- The project preflight, not Jolt, currently makes `JOLT_CHEZ_CSV` and a modified `PATH` mandatory.
- Jolt's signable path still needs a separate Chez installation; removing that upstream requirement
  is outside this RFC.

## Decisions

- On 2026-10-06, the repository owner requested a normal repository change that can be pushed to the
  public GitHub repository.
- On 2026-10-06, the repository owner required the change and its history to contain no private
  provenance.
- The proposed normal path is installation plus `jolt pkg`; explicit environment configuration is
  retained only for nonstandard installations.

## Commands and evidence

- Baseline `env -u JOLT_CHEZ_CSV jolt build --signable ...` succeeded when Chez Scheme 10.4.1 was
  available on `PATH`, confirming that routine project exports are unnecessary.
- `python3 .claude/skills/spec-driven/scripts/rfc.py status` reported no pre-existing public RFC.
