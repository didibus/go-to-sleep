# Go To Sleep development guide

## What this is

Go To Sleep is a local macOS menu-bar application that enforces a weekly bedtime schedule. During a
scheduled block it locks the current session and immediately locks it again after an unlock.

The public GitHub repository is the canonical development source. Its root is an imported, accepted
0.1.0 baseline; pre-publication RFC history was intentionally omitted. `specs/current/` describes
implemented behavior.

All changes use the tracked `spec-driven` skill:

- current behavior: `specs/current/`;
- active changes: `specs/changes/active/`;
- archived changes: `specs/changes/archive/`;
- skill: `.claude/skills/spec-driven/SKILL.md`;
- stable project identity: `specs/project-id.txt`.

Before granting an agent permission to run repository hooks, inspect `.claude/settings.json` and
`.codex/hooks.json`. The RFC commands can always be run directly:

```sh
python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" status
python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" check
```

`.agents/skills/` provides compatibility links to the same tracked skills. The `brepl` skill and
`.brepl/hooks.edn` configure an optional external REPL tool; this repository does not bundle its
executable and does not assume where it is installed. If it is available, load
`.claude/skills/brepl/SKILL.md` before using it. Do not rely on a `bb` command; keep project tasks in
`deps.edn` and run them through Jolt.

## Product rules that must not regress

- While a sleep block is active, the session stays locked. Unlocking triggers an immediate relock.
- After exactly 24 hours of continuous active state for one occurrence, enforcement effects stop;
  maintenance continues and a later occurrence works normally. A one-hour fall-back transition can
  therefore leave the final 59 minutes of a long valid occurrence unenforced.
- A block is frozen from eight hours before its start until it ends.
- While any occurrence is frozen or active, the complete weekly schedule is growth-only:
  - an empty weekday may gain a block;
  - an existing block may keep or move to an earlier start and keep or move to a later end;
  - every changed existing block must strictly contain its previous span;
  - every current frozen occurrence must remain contained in instant time across DST resolution.
- Identical, shorter, deleted, disabled, or shifted-without-containment schedules are rejected while
  growth-only. A prohibited change on one day rejects the complete candidate.
- Every growth-only save uses exact daemon-backed confirmation and the authoritative deadline.
  Immediate activation warns that the screen will lock.
- Uninstall is actionable only from a latest complete authoritative open status. The daemon
  independently refuses uninstall while frozen or active.
- Consecutive occurrences must leave at least one open hour before the next eight-hour freeze:
  blocks start at least nine hours after the previous block ends.
- Time-zone adoption is maintenance, not a user schedule edit. It is deferred unless current frozen
  occurrences remain contained and the complete window rule stays valid.
- The daemon—not the UI or config file—validates every mutation and owns enforcement.
- Circumvention should require deliberate privileged action. Administrator access can ultimately
  defeat a local tool; the design does not claim otherwise.

The complete installed real-lock, daemon-only relock, reboot/login relock, and natural-end sequence
remains deferred and must never be described as passed.

## Stack

- Jolt 0.8.16 compiles Clojure to Chez Scheme; there is no JVM.
- Dependencies are Git coordinates pinned by SHA in `deps.edn`.
- The UI uses `glitter-uikit` and `glitter-core`. Do not add the GTK `glitter` package.
- Native macOS APIs use `jolt.ffi`.
- `jolt.process`, `jolt.fs`, and `clojure.edn` are built in.
- Release builds require Apple Clang and Chez Scheme 10.4.1's `tarm64osx` development directory,
  selected through `JOLT_CHEZ_CSV` and `PATH`.

The app parses TZif data from `/var/db/timezone/zoneinfo` itself. Do not replace it with the
transitive time library without proving DST and unknown-zone behavior.

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

The AppKit smoke opens a temporary GUI window but does not install or enforce. Package installation,
real locking, power changes, logout, sleep, and reboot are human-only steps.

For a release:

```sh
bash "$(git rev-parse --show-toplevel)/dev/check-release.sh" \
  vMAJOR.MINOR.PATCH YYYY-MM-DD
```

The repository owner's local Git identity is configured with
`bash "$(git rev-parse --show-toplevel)/dev/setup-maintainer-repository.sh"`. That command is only
for didibus; contributors use their own identities.

The imported 0.1.0 baseline is a bootstrap exception. Its normal release invocation is deliberately
refused because rebuilding cannot reproduce or replace the already accepted package. Maintainers may
run the checker with `--verification-only` to create separately named `.rebuilt` evidence, which is
not a release handoff and must never be uploaded. See `specs/current/release.md` for that command and
for the hardened future publication command.

## Jolt and FFI constraints

- Call `(System/exit n)` directly. Guarding it through `resolve` silently fails.
- A test runner must count namespace load failures and exit nonzero itself.
- `ffi/write` is `(ffi/write ptr type value offset)`.
- `defcfn`, `foreign-callable`, and callbacks require literal argument vectors.
- Bind `objc_msgSend` once per concrete signature; never use varargs for it on arm64.
- Map Objective-C BOOL to `:char`, NSInteger/NSUInteger to `:int64`, and CGFloat to `:double`.
- Load frameworks by full framework path, not through dependency-native declarations.
- Waiting native calls are `:blocking`; callbacks arriving from foreign threads or while the main
  thread is parked are `:collect-safe`.
- Objective-C exceptions abort the process, so validate inputs before sending messages.
- Foreign memory must be explicitly scoped or freed.
- Completed `jolt.process` children retain native pipes unless callers close them. Use
  `gotosleep.subprocess` for long-lived callers.
- `ffi/read-bytes` returns a String, not a byte array. Read raw bytes with `ffi/read` and `:uint8`.
- The shim lacks `ByteArrayOutputStream.write(byte[],int,int)` and `fsync`; durable persistence uses
  native `open`/`write`/`fcntl F_FULLFSYNC`/`rename`.
- A daemon `-main` must block explicitly after starting its threads.
- LaunchServices refuses script app executables. Build directly into the bundle's `Contents/MacOS`
  path and use Jolt's signable output mode for payloads that will be codesigned.
- There is no configured linter. Any future Clojure lint setup must include glitter-uikit's Jolt FFI
  hook or generated `defcfn` names will appear unresolved.

## AppKit model

- One application-state atom drives a pure state-to-hiccup view.
- Events are data, not closures.
- `glitter-uikit.app/run` owns and blocks in the AppKit run loop.
- Touch AppKit only on the main thread through `glitter-uikit.app/on-gui`.
- State swaps from worker threads are allowed because rendering is marshalled to the GUI thread.
- AppKit has no CSS; `:class` and `:style` do not provide styling.
- Keep ordinary tests headless.

## Architecture

Three binaries are built from one `deps.edn`:

- `gotosleepd` — root LaunchDaemon and authority for schedule, time, persistence, locking, fallback
  actions, supervision, IPC, and uninstall.
- `gotosleep-lock` — short-lived session-user helper that requests one immediate screen lock.
- `GoToSleep.app` — unprivileged menu-bar agent, settings UI, notifications, and secondary relock
  path. Enforcement does not depend on it.

The functional core and imperative shells are split across:

- `src/gotosleep/tz.clj` — TZif parsing and civil/instant arithmetic;
- `src/gotosleep/schedule.clj` — schedule, occurrence, freeze, window, and confirmation rules;
- `src/gotosleep/clock.clj` — trusted time;
- `src/gotosleep/protocol.clj` — strict newline-framed EDN IPC;
- `src/gotosleep/observe.clj`, `os.clj`, and `cf.clj` — pure observation parsing and live OS/FFI;
- `src/gotosleep/daemon/` — pure transitions plus persistence, jobs, socket, power, and main loop;
- `src/gotosleep/agent/` — UI reduction, client, notification, AppKit shell, and relock path;
- `src/gotosleep/lock/main.clj` — one-shot lock helper;
- `src/gotosleep/subprocess.clj` — completed-process pipe cleanup.

## Repository workflow

- Use `main` and one `rfc/NNNN-slug` branch per change.
- The human approves an exact RFC revision before implementation and separately accepts an exact
  implementation commit after verification.
- Keep `specs/current/` unchanged during implementation review. Fold accepted behavior only after
  implementation acceptance.
- Preserve unrelated changes in a dirty worktree.
- Use `rg` for searches and `apply_patch` for edits.
- Commits and merges require human permission unless explicitly delegated.
- Every release is an RFC and produces a clean annotated immutable version tag.
- Push only the explicit tested refs to the exact audited URL, using the isolated and hardened
  command in `specs/current/release.md`; never use `--all`, `--tags`, or `--mirror`.

## Safety boundaries

Without an explicit human step, an agent never:

- runs `sudo`;
- installs a package;
- loads or unloads system or GUI launchd jobs;
- runs the lock helper or invokes the private screen-lock symbol;
- changes power, clock, time-zone, or network-time settings;
- reboots, sleeps, logs out, or triggers notification permission;
- deletes outside the repository and a neutral temporary directory.

Reading system state, building, and running the non-destructive suites are allowed. Treat generated
output as disposable and never put a test bypass in a shipped binary.
