# RFC 0001 — Auto-detect the signable-build toolchain

| Field | Value |
| --- | --- |
| ID | go-to-sleep/0001 |
| Phase | Verified |
| Approved by | didibus (repository owner), 2026-10-06 |
| Approved revision | `e29846741b910d8daca308cc48c01b2f6d7c03ef` |

Progress, estimates, day counts, and implementation acceptance live in `tasks.md`, not here.

## Intent

Confirmed by the repository owner on 2026-10-06.

**Outcome.** After Chez Scheme 10.4.1 is installed normally and available through `PATH`, running
`jolt pkg` MUST discover and use it without requiring manual `JOLT_CHEZ_CSV` or `PATH` exports.

**Motivation.** The current project preflight is stricter than Jolt itself and makes the documented
release command fail after an otherwise sufficient tool installation. Release builds should retain
their strict signable-output checks without requiring developers to know the dependency's internal
installation layout.

**Success signals.**

1. `brew install chezscheme` followed by `jolt pkg` is sufficient on a supported development Mac.
2. An explicit toolchain override remains available for nonstandard installations.
3. Missing, incomplete, or incompatible toolchains fail before existing staging output is replaced
   and report an actionable remedy.
4. Documentation, tests, commits, and generated release surfaces contain no personal, employer,
   device, hostname, or local-path provenance.

**Constraints.** Jolt remains pinned to 0.8.16, Chez Scheme remains pinned to 10.4.1, release
executables remain structurally signable and ad-hoc signed, and the package remains neither
Developer ID signed nor notarized.

## Scope and non-goals

In scope:

- automatic discovery of `chez` from `JOLT_CHEZ`, then `PATH`;
- derivation and validation of the matching `tarm64osx` development directory;
- continued support for an explicit `JOLT_CHEZ_CSV` override;
- focused success and failure-path tests;
- public build documentation that requires installation but no routine environment exports.

Out of scope:

- removing Jolt's underlying need for Chez Scheme;
- downloading or installing dependencies from the build;
- changing the Jolt, Chez Scheme, macOS, or architecture support matrix;
- Developer ID signing, notarization, package signing, or application behavior changes.

## Current behavior

`packaging/common.sh` refuses every signable build unless `JOLT_CHEZ_CSV` is explicitly set and
`PATH` resolves `chez` from that exact directory. The README and repository guidance therefore tell
developers to export both values manually after installing Chez Scheme.

Jolt 0.8.16 can discover a normal Chez installation from the interpreter on `PATH`; the project
preflight prevents that supported path from being used.

## Proposed behavior

`require_signable_toolchain` in `packaging/common.sh` MUST resolve the Chez toolchain in this order:

1. If non-empty `JOLT_CHEZ_CSV` is supplied, canonicalize and validate that directory.
2. Otherwise, resolve the interpreter from non-empty `JOLT_CHEZ`, then from `command -v chez`.
3. If the interpreter's directory itself is a complete Chez development directory, use it.
4. Otherwise derive `lib/csv10.4.1/tarm64osx` relative to the interpreter's canonical installation
   prefix.

The selected directory MUST contain executable `chez` and regular `scheme.h`, `libkernel.a`,
`petite.boot`, and `scheme.boot` files. The selected interpreter MUST report version `10.4.1`.

After validation, the preflight MUST export canonical `JOLT_CHEZ_CSV` and `JOLT_CHEZ` values for all
three `jolt build --signable` invocations. It MUST NOT modify the caller's shell environment or
require the development-directory executable to be manually prepended to `PATH`.

Toolchain validation MUST finish before the staging directory is removed. Failure MUST return status
2 with a generic message that recommends installing Chez Scheme or supplying
`JOLT_CHEZ_CSV`; it MUST NOT expose unrelated environment or machine details.

The README and shared repository guidance MUST document `brew install chezscheme` and Apple Clang as
the normal preparation. They MUST describe the override only as troubleshooting for a nonstandard
installation.

## Compatibility and migration

Existing callers that export `JOLT_CHEZ_CSV` remain supported. Existing callers that also alter
`PATH` continue to work, but the alteration is no longer required. There is no data or package-format
migration.

## Acceptance criteria

- **AC1 — Normal discovery.** With `JOLT_CHEZ` and `JOLT_CHEZ_CSV` unset and a valid Chez Scheme
  10.4.1 installation on `PATH`, `jolt pkg` succeeds and `bash dev/check-pkg.sh` passes.
- **AC2 — Explicit override.** With the normal interpreter path hidden and a valid
  `JOLT_CHEZ_CSV` supplied, the preflight selects that directory and permits a signable build.
- **AC3 — Fail before mutation.** With both overrides unset and no discoverable Chez interpreter,
  the stage build exits 2, preserves a sentinel in the requested staging directory, and recommends
  installation or the explicit override.
- **AC4 — Reject incompatible inputs.** Focused tests reject a missing required file and a Chez
  interpreter that does not report 10.4.1.
- **AC5 — Portable documentation.** Repository documentation describes the no-export normal path
  and the optional override consistently; `jolt -M:test` passes.
- **AC6 — Provenance safety.** `bash dev/check-provenance.sh`, `git diff --check`, and RFC structural
  checks pass on the complete change.

## Current-spec changes

- `specs/current/install.md`

## Open questions

None.

## Appendix — rationale and alternatives

Delegating discovery entirely to `jolt build --signable` was rejected because the project preflight
must validate the complete toolchain before replacing staging output. Requiring the explicit
development-directory export was rejected because it duplicates Jolt's installation discovery and
exposes an internal dependency layout in the routine workflow.
