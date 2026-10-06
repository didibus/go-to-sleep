# Notes — RFC 0001

Written for whoever picks this up next, who has none of your context. Update it at every task
boundary.

## Resume context

- **Phase and revision** — Complete; approved RFC revision
  `e29846741b910d8daca308cc48c01b2f6d7c03ef`, accepted implementation
  `0b827a90869c498ac74397605af2c66b47ed0dc8`.
- **Branch** — `rfc/0001-auto-detect-chez`; completion integration is being committed.
- **Done** — T1–T3. Toolchain discovery, focused regressions, documentation, a real no-export
  package build, the complete package audit, the full test suite, and provenance checks passed at
  the implementation commit. The repository owner accepted that commit, and its behavior is folded
  into `specs/current/install.md`.
- **In progress** — archive and completion commit.
- **Blocked** — none.
- **Next action** — commit the current-spec fold and archive, record that integration commit, then
  request separate approval to merge the RFC branch into `main`.

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
- On 2026-10-06, the repository owner accepted implementation commit
  `0b827a90869c498ac74397605af2c66b47ed0dc8`.
- The proposed normal path is installation plus `jolt pkg`; explicit environment configuration is
  retained only for nonstandard installations.

## Commands and evidence

- Imported baseline: `bash test/packaging_toolchain.sh` failed at PATH discovery with the old
  mandatory-`JOLT_CHEZ_CSV` error, proving the regression test exercised the intended behavior.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`:
  `bash test/packaging_toolchain.sh` passed PATH discovery,
  `JOLT_CHEZ`, `JOLT_CHEZ_CSV`, missing-toolchain, incomplete-directory, and wrong-version cases.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`:
  `env -u JOLT_CHEZ -u JOLT_CHEZ_CSV jolt pkg` exited 0, built and strictly verified all three
  payload executables, and wrote the package and checksum without manual environment exports.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`:
  `bash dev/check-pkg.sh` passed the complete package audit,
  including strict signatures, package structure, checksum, pre-mutation missing-toolchain failure,
  and the focused toolchain cases.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`: `jolt -M:test` ran
  211 tests and 6,862 assertions with zero failures or errors and zero namespace load failures.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`:
  `bash dev/check-provenance.sh`, Bash syntax checks, `git diff --check`, `git fsck --full --strict`,
  and `rfc.py check --verbose` passed.
- Implementation commit `0b827a90869c498ac74397605af2c66b47ed0dc8`: explicit
  private-provenance scans over working-tree paths, content, reachable Git objects, and Git metadata
  returned no matches.
