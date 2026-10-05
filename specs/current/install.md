# Build, install, upgrade, and removal

## Supported artifact

Release 0.1.0 is built for Apple silicon (`arm64`) and declares macOS 26.0 as its minimum system
version. There is no x86_64 build or support promise for earlier macOS versions.

The stable identities are:

- app bundle and associated bundle: `com.rubberducking.gotosleep`;
- LaunchAgent: `com.rubberducking.gotosleep.agent`;
- LaunchDaemon: `com.rubberducking.gotosleep.daemon`;
- installer receipt: `com.rubberducking.gotosleep.pkg`.

`src/gotosleep/version.clj` is the single production source of version `0.1.0`. Daemon status and the
generated bundle, package, artifact, checksum, and release metadata all derive from it.

## Build

Release builds require Jolt 0.8.16, Apple Clang, and the Chez Scheme 10.4.1 `tarm64osx` development
directory. `JOLT_CHEZ_CSV` must identify that directory, and its `chez` executable must resolve first
through `PATH`.

```sh
cd "$(git rev-parse --show-toplevel)"
jolt -M:test
jolt -M:smoke
bash dev/e2e/run.sh
jolt -A:test -m gotosleep.agent.cancel-smoke
jolt stage
jolt pkg
bash dev/check-pkg.sh
```

`jolt stage` builds three signable binaries under `target/stage`, adds generated bundle metadata,
then ad-hoc signs and strictly verifies the daemon, lock helper, app bundle, and app executable.

`jolt pkg` atomically publishes exactly:

- `target/release/GoToSleep-0.1.0.pkg`;
- `target/release/SHA256SUMS`.

The package builder recreates both raw Payload and Scripts CPIO archives with `root:wheel`
membership. The package audit checks exact inventories, ownership and modes, identifiers and version
metadata, architecture, strict payload signatures, tamper rejection, state/AppleDouble exclusion,
sanitized XAR metadata, expected unsigned-installer assessment, and the checksum.

## Installed paths

- `/Applications/GoToSleep.app`
- `/Library/Application Support/GoToSleep/bin/gotosleepd`
- `/Library/Application Support/GoToSleep/bin/gotosleep-lock`
- `/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist`
- `/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist`
- `/Library/Application Support/GoToSleep/state.edn`
- `/Library/Logs/GoToSleep`
- `/var/run/gotosleep.sock`

Installation requires administrator authority. Before mutation, the package refuses to replace an
existing `/Applications/GoToSleep.app` whose readable bundle identifier is not the expected public
identifier. Postinstall establishes ownership and modes, bootstraps the daemon, and fails if the
daemon does not reach running state. The daemon then supervises and starts per-session agents.

## Distribution and trust

The app and standalone payload binaries are structurally valid ad-hoc-signed code. The `.pkg` itself
is deliberately neither Developer ID signed nor notarized, so Gatekeeper is expected to reject it
until the user deliberately approves it or removes quarantine after verification.

`SHA256SUMS` verifies equality with the package bytes advertised at the release location; it does not
authenticate the publisher. Administrator authorization also does not replace publisher signing.

## Upgrade and removal

Same-identity upgrades preserve `state.edn`, stop only the public jobs, replace public payload files,
and restart the daemon and agents.

Open-state uninstall is initiated from the menu and performed by the daemon. Frozen or active state
refuses uninstall. Teardown is best-effort and records failures in
`/Library/Logs/GoToSleep-uninstall.log`, outside the normal removed log directory.
