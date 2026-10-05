# Enforcement

## Authority

The root LaunchDaemon is the enforcement authority. The menu-bar agent may disappear without making
an active block editable or stopping daemon-side locking. launchd keeps both jobs alive, and the
daemon supervises logged-in users' agents every five seconds while frozen/active and every minute
while open.

## Active-block loop

The daemon observes console sessions, lock state, full-wake capability, monotonic time, and the
system `SleepDisabled` property once per second.

While a block is active and a console session is unlocked:

1. request an immediate screen lock on every tick;
2. after five seconds of awake, unlocked time, request system sleep;
3. if `SleepDisabled` is true, durably record restoration intent, temporarily allow sleep, and retry;
4. after fifteen seconds, if sleep was requested but not observed, request logout, no more than once
   per thirty seconds per user.

When the block ends, any sleep setting changed by the fallback chain is restored after a successful
job result. The application creates no power assertion and does not normally change power settings.
Dark wake emits no enforcement effects.

The user agent also requests a relock when it observes an active, unlocked session. This is secondary
only; the daemon remains authoritative.

## Safety stop

After exactly 24 hours of continuous activity for one occurrence, enforcement effects stop for that
occurrence. Logging, persistence, network-time maintenance, zone maintenance, schedule authority,
and supervision continue. A later occurrence enforces normally.

A valid block spanning a one-hour fall-back transition can remain active for 24 hours 59 minutes, so
the safety stop may leave its final 59 minutes unenforced. This is an intentional safety exception.

## Failure behavior

- Missing or invalid persisted state starts with an empty schedule after quarantine is attempted.
- Failed persistence rejects a schedule mutation and prevents persistence-dependent fallback effects.
- Failed session observation is treated conservatively during an active block by using the last
  known user and assuming unlocked.
- A reboot gap is logged when persisted trusted time shows that enforcement may have been absent.

## Threat model and residuals

The design makes circumvention require deliberate privileged action. It does not defend against a
user intentionally using administrator privileges, Recovery mode, replacing system files, or
disabling platform security.

Screen locking uses the private, unsupported macOS symbol `SACLockScreenImmediate`. Apple may change
or remove that API in a future macOS update.

The combined installed real-lock, daemon-only relock, reboot/login relock, and natural-end sequence
remains deferred and unverified.
