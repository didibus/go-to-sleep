# Changelog

All notable changes to Go To Sleep will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The entries below describe the 0.1.0 release published on 2026-10-05.

## [Unreleased]

## [0.1.0] - 2026-10-05

### Added

- Weekly sleep blocks with immediate relocking throughout an active block.
- An eight-hour freeze window with daemon-enforced growth-only schedule changes.
- A root LaunchDaemon, menu-bar settings agent, and one-shot session lock helper.
- Trusted-clock, time-zone, daylight-saving, reboot, and daemon-recovery handling.
- Open-state uninstall with frozen and active refusal.
- An arm64 package for macOS 26 or later, with SHA-256 verification.

### Security

- Schedule validation and enforcement are owned by the privileged daemon rather than the untrusted
  menu-bar process or on-disk configuration.
- Continuous active enforcement effects stop after 24 hours as a fail-safe.

[Unreleased]: https://github.com/didibus/go-to-sleep/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/didibus/go-to-sleep/releases/tag/v0.1.0
