# Architecture

Baseline: imported, accepted Go To Sleep 0.1.0 public release.

Go To Sleep is a local macOS application with a privileged enforcement process and an unprivileged
menu-bar interface. The daemon is the sole authority for schedule mutation and enforcement; the UI,
IPC clients, and persisted file contents are treated as untrusted inputs.

## Processes

- `gotosleepd` runs as the root LaunchDaemon `com.rubberducking.gotosleep.daemon`. It owns trusted
  time, schedule validation, persistence, screen-lock enforcement, fallback actions, agent
  supervision, IPC, and uninstall.
- `gotosleep-lock` is a short-lived helper launched in a GUI user's bootstrap namespace. It invokes
  `SACLockScreenImmediate` once and exits.
- `GoToSleep.app` runs as the per-user LaunchAgent `com.rubberducking.gotosleep.agent`. Its bundle
  identifier is `com.rubberducking.gotosleep`. It owns the menu-bar item, settings window,
  notifications, and a secondary relock path. Enforcement does not depend on it.

Production entry points are `src/gotosleep/daemon/main.clj`,
`src/gotosleep/lock/main.clj`, and `src/gotosleep/agent/main.clj`.

## Functional core and shells

The scheduling, time-zone, trusted-clock, protocol, observation parsing, UI reduction, and daemon
state transitions are pure where practical. Native APIs, files, processes, AppKit, launchd, and the
Unix socket are isolated in shell namespaces.

Important boundaries:

- `gotosleep.schedule` computes occurrences and validates edits.
- `gotosleep.clock` derives trusted wall time from monotonic time and persisted anchors.
- `gotosleep.daemon.core` returns a new state plus declarative effects.
- `gotosleep.daemon.main` persists accepted state before exposing success and executes effects.
- `gotosleep.agent.ui` reduces daemon status and user events to UI state and requests.
- `gotosleep.version` is the single production source of the semantic product version.

## IPC

The control socket is `/var/run/gotosleep.sock`. Requests and responses are newline-framed EDN.
Requests are limited to 64 KiB, decoded as strict UTF-8, parsed without executable/tagged literals,
and checked for exact operation shapes.

`:status` is public. `:set-schedule` and `:uninstall` are accepted only from root or the current
console user's peer UID. Mutating requests refresh wall time, monotonic time, and console-session
observations at their evaluation boundary.

## State and logs

The daemon persists a format-1 EDN subset at
`/Library/Application Support/GoToSleep/state.edn`, mode 0600. Writes use a private temporary file,
`F_FULLFSYNC`, and atomic rename. Invalid state is quarantined when possible; startup then uses an
empty schedule.

Daemon logs live under `/Library/Logs/GoToSleep`. The user agent logs under
`~/Library/Logs/GoToSleep`. State and logs remain local.

## Verification status

The accepted baseline passed the headless unit/property/integration suite, OS smoke, recording E2E,
non-enforcing AppKit smoke, package build and archive audit, and public history/provenance checks.
The installed daemon, agent, receipt, bundle identity, and Settings reopen path were also checked.

The complete installed real-lock, daemon-only relock, reboot/login relock, and natural-end sequence
remains deferred and must not be described as passed. Automated tests never lock, sleep, log out,
reboot, or alter system configuration.
