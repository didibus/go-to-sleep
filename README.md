# Go To Sleep

Go To Sleep is a small menu-bar app that enforces a weekly bedtime schedule. During a scheduled
sleep block, it locks the current macOS session and immediately locks it again after an unlock.

Version 0.1.0 is for Apple silicon (`arm64`) Macs running macOS 26 or later. It has
no x86_64 build and no support promise for older macOS releases.

## Understand enforcement before installing

Go To Sleep is deliberately harder to bypass than an ordinary menu-bar app. Installation creates a
root LaunchDaemon that owns the schedule, validates changes, survives login and reboot, supervises
the user agent, and requests screen locks. Quitting the menu-bar app does not stop enforcement.
Someone with administrator access can ultimately defeat a local tool, but doing so is intended to
require deliberate privileged actions.

Each block freezes eight hours before it begins and remains frozen until it ends. While any block is
frozen or active, the complete weekly schedule is growth-only: you may add a block to an empty day or
extend an existing block earlier and/or later, but you may not shorten, move, disable, or delete an
existing block. Growth requires confirmation. Unrestricted editing returns when no block is frozen
or active.

As a deliberate safety limit, enforcement effects stop after 24 hours of continuous active state.
Maintenance continues, and later blocks can enforce normally. A block crossing the one-hour daylight
saving fall-back transition may therefore be unenforced for its final 59 minutes.

Screen locking uses the private, unsupported macOS symbol `SACLockScreenImmediate`. Apple may change
or remove that API in a future macOS update, which could break locking even when the app still runs.

## Download and verify

A release has these two attachments:

- `GoToSleep-0.1.0.pkg`
- `SHA256SUMS`

Download both into the same directory, then verify the package bytes:

```sh
cd ~/Downloads
shasum -a 256 --strict -c SHA256SUMS
```

The checksum proves only that your package is byte-for-byte identical to the package advertised at
the release location. It does not authenticate the publisher. Trust the release location and inspect
or otherwise trust the published source before installing. Do not bypass Gatekeeper unless the
checksum matches and you independently trust both.

## Install

The package is intentionally unsigned and not notarized. Gatekeeper is therefore expected to refuse
the first installation attempt; this is not a signed publisher-verification flow.

### Approve through macOS

1. Double-click `GoToSleep-0.1.0.pkg`.
2. After macOS blocks it, open **System Settings → Privacy & Security**.
3. In the Security section, choose **Open Anyway** for the package.
4. Authenticate to approve the exception, choose **Open Anyway** again if prompted, and complete the
   Installer steps.

The wording or placement may vary between macOS updates. Verify `SHA256SUMS` first even when using
the approval UI.

### Deliberate command-line installation

If you have verified the checksum and independently trust the release location and source, you can
explicitly remove the downloaded-file quarantine marker and run Installer:

```sh
cd ~/Downloads
shasum -a 256 --strict -c SHA256SUMS
xattr -d com.apple.quarantine GoToSleep-0.1.0.pkg
sudo installer -pkg GoToSleep-0.1.0.pkg -target /
```

`sudo` authorizes changes to the Mac. It does not authenticate the package, identify its publisher,
replace code signing, or make the package trusted.

After installation, use the moon icon in the menu bar to configure the weekly schedule. If a crowded
menu bar places the icon behind a MacBook display notch, open **Go To Sleep** from Applications or
Spotlight to bring Settings forward.

## Uninstall

While the authoritative state is open, choose **Uninstall…** from the moon menu and confirm. Uninstall
is unavailable while any block is frozen or active, just as schedule-shrinking changes are.

## Build and test from source

Release builds require:

- Apple silicon and macOS 26 or later;
- Jolt 0.8.16;
- Chez Scheme 10.4.1, including its `tarm64osx` development directory;
- Apple Clang, normally installed with Xcode Command Line Tools.

Homebrew provides the normal Chez Scheme installation:

```sh
cd "$(git rev-parse --show-toplevel)"
brew install chezscheme
xcode-select --install
command -v chez
chez --version
xcrun --find clang
jolt --version
```

The build discovers `chez` from `PATH` and validates its matching development directory. A
nonstandard installation may set `JOLT_CHEZ_CSV` to the exact `tarm64osx` development directory;
routine Homebrew builds need no environment exports. The build preflight rejects missing or
incompatible prerequisites before replacing staging output.

Run the automated checks:

```sh
cd "$(git rev-parse --show-toplevel)"
jolt -M:test
jolt -M:smoke
bash dev/e2e/run.sh
jolt -A:test -m gotosleep.agent.cancel-smoke
```

The AppKit smoke opens a temporary GUI window but does not install the app or lock the screen.

Build the staged app and binaries, create the release pair, and audit the package:

```sh
cd "$(git rev-parse --show-toplevel)"
jolt stage
jolt pkg
bash dev/check-pkg.sh
```

The package command writes `target/release/GoToSleep-0.1.0.pkg` and
`target/release/SHA256SUMS`.

## Source layout

- `src/gotosleep/daemon/` — root daemon core, IPC server, persistence, jobs, power, and logging
- `src/gotosleep/agent/` — menu-bar agent, settings UI, notifications, and daemon client
- `src/gotosleep/lock/` — one-shot session lock helper
- `src/gotosleep/schedule.clj` and `src/gotosleep/tz.clj` — schedule rules and time-zone arithmetic
- `test/` — headless unit, property, and in-process integration tests
- `smoke/` — non-destructive checks against the local operating system
- `dev/e2e/` — recording-only end-to-end checks
- `packaging/` — app bundle, launchd, signing, and installer build inputs

## License

Go To Sleep is available under the [MIT License](LICENSE).
