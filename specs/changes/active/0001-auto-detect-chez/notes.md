# Notes — RFC 0001

Written for whoever picks this up next, who has none of your context. Update it at every task
boundary.

## Resume context

- **Phase and revision** — Verified; approved RFC revision
  `e29846741b910d8daca308cc48c01b2f6d7c03ef`.
- **Branch** — `rfc/0001-auto-detect-chez`; dirty with the verified implementation candidate.
- **Done** — T1–T3. Toolchain discovery, focused regressions, documentation, a real no-export
  package build, the complete package audit, the full test suite, and provenance checks pass.
- **In progress** — implementation handoff.
- **Blocked** — committing and accepting the implementation require repository-owner approval.
- **Next action** — obtain approval to commit the implementation candidate, then present that exact
  commit for acceptance before folding `specs/current/install.md`.

## Discoveries and uncertainty

- Jolt 0.8.16 successfully performs a signable build without either override when a normal Chez
  Scheme 10.4.1 installation is available through `PATH`.
- The project preflight, not Jolt, currently makes `JOLT_CHEZ_CSV` and a modified `PATH` mandatory.
- Jolt's signable path still needs a separate Chez installation; removing that upstream requirement
  is outside this RFC.
- The package generated while verifying this source is a disposable build artifact. It differs from
  the immutable accepted 0.1.0 package and MUST NOT replace or be uploaded as that release asset.

## Decisions

- On 2026-10-06, the repository owner requested a normal repository change that can be pushed to the
  public GitHub repository.
- On 2026-10-06, the repository owner required the change and its history to contain no private
  provenance.
- On 2026-10-06, the repository owner approved RFC revision
  `e29846741b910d8daca308cc48c01b2f6d7c03ef`.
- The proposed normal path is installation plus `jolt pkg`; explicit environment configuration is
  retained only for nonstandard installations.

## Commands and evidence

- Imported baseline: `bash test/packaging_toolchain.sh` failed at PATH discovery with the old
  mandatory-`JOLT_CHEZ_CSV` error, proving the regression test exercised the intended behavior.
- Dirty implementation candidate: `bash test/packaging_toolchain.sh` passed PATH discovery,
  `JOLT_CHEZ`, `JOLT_CHEZ_CSV`, missing-toolchain, incomplete-directory, and wrong-version cases.
- Dirty implementation candidate:
  `env -u JOLT_CHEZ -u JOLT_CHEZ_CSV jolt pkg` exited 0, built and strictly verified all three
  payload executables, and wrote the package and checksum without manual environment exports.
- Dirty implementation candidate: `bash dev/check-pkg.sh` passed the complete package audit,
  including strict signatures, package structure, checksum, pre-mutation missing-toolchain failure,
  and the focused toolchain cases.
- Dirty implementation candidate: `jolt -M:test` ran 211 tests and 6,862 assertions with zero
  failures or errors and zero namespace load failures.
- Dirty implementation candidate: `bash dev/check-provenance.sh`, Bash syntax checks,
  `git diff --check`, and `rfc.py check --verbose` passed.
- Dirty implementation candidate: explicit private-provenance scans over working-tree paths,
  content, and existing Git metadata returned no matches.
