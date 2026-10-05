# Repository engineering adapter

`AGENTS.md` contains the product, stack, architecture, commands, and safety rules. This file defines
the repository-specific RFC and release workflow.

## Baseline and identity

- The public root commit is an imported, accepted Go To Sleep 0.1.0 baseline.
- Its pre-publication RFC history was intentionally not published.
- Public RFC 0001 is the first change to that baseline, not the historical implementation change.
- `specs/project-id.txt` is the stable RFC namespace `go-to-sleep`, independent of checkout name.
- The main branch is `main`; each change uses `rfc/NNNN-slug`.

The repository owner uses:

```text
didibus <601540+didibus@users.noreply.github.com>
```

On the owner's development machines only:

```sh
bash "$(git rev-parse --show-toplevel)/dev/setup-maintainer-repository.sh"
```

Other contributors use their own Git identities.

## RFC authority

- `specs/current/` and the code describe implemented behavior.
- An active RFC defines one proposed delta after the owner approves its exact revision.
- The owner separately accepts an exact implementation commit after verification.
- Local edits and non-destructive checks are allowed. Commits and merges require the owner's
  approval unless the owner explicitly delegates them.
- Accepted behavior is folded into `specs/current/` before the RFC becomes Complete and is archived.

Use the complete tracked skill under `.claude/skills/spec-driven/`. Repository hooks provide a write
guard when the developer has inspected and trusted them. The direct commands work without hooks:

```sh
python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" status
python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" check
```

## Deferred validation

The combined installed real-lock, daemon-only relock, reboot/login relock, and natural-end sequence
has not been completed. It must not be described as passed. Automated and recording-only checks do
not replace that debt.

## Commands

```sh
cd "$(git rev-parse --show-toplevel)"
jolt -M:test
jolt -M:smoke
bash dev/e2e/run.sh
jolt -A:test -m gotosleep.agent.cancel-smoke
jolt stage
jolt pkg
bash dev/check-pkg.sh
bash dev/check-provenance.sh
```

For releases, follow `specs/current/release.md` and run:

```sh
bash "$(git rev-parse --show-toplevel)/dev/check-release.sh" \
  vMAJOR.MINOR.PATCH YYYY-MM-DD
```

## Agent constraints

Without an explicit human step, an agent never:

- runs `sudo`;
- installs a package;
- loads or unloads system or GUI launchd jobs;
- runs the lock helper or calls the private screen-lock API;
- changes power, clock, time-zone, or network-time settings;
- reboots, sleeps, logs out, or triggers a notification permission prompt;
- deletes outside the repository and a neutral temporary directory.

Reading system state, building, and running the non-destructive test suites are allowed. Generated
output is disposable. Never put a test bypass in a shipped binary.

## Release rules

- Every release is an RFC.
- Version, changelog, README artifact examples, current release spec, and annotated tag must agree.
- For versions after 0.1.0, `dev/check-release.sh` tests the exact tag in isolation and produces the
  only uploadable handoff.
- A normal 0.1.0 invocation is refused. Its `--verification-only` mode creates non-uploadable,
  separately named `.rebuilt` evidence and never creates a release handoff.
- Push only explicit tested branch/tag refs to the exact audited URL with external Git configuration
  isolated and hooks, follow-tags, signing, and mirroring disabled for that operation.
- Never move or replace a published tag.
- Upload only the audited package and `SHA256SUMS`; the checksum does not authenticate the publisher.
