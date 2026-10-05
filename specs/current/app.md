# Menu-bar application

`GoToSleep.app` is an `LSUIElement` application launched for each Aqua login session. It presents a
menu-bar status item and a hidden settings window; it does not appear in the Dock.

## Menu and status

The agent polls the daemon once per second. The menu title reports the nearest relevant block,
including countdown, frozen, active, safety-stopped, or daemon-unavailable state. Settings remains
available when the daemon is healthy enough to supply a complete status.

macOS may place the status item behind a MacBook display notch when the menu bar is crowded. Opening
`GoToSleep.app` from Applications or Spotlight sends an application-reopen event that brings the
existing Settings window forward. Closing Settings does not terminate the agent, so the same path
works repeatedly.

Uninstall is visible but actionable only with a latest complete authoritative `:open` status.
Startup, malformed status, daemon-down, frozen, and active states disable the native menu item and
invalidate queued uninstall actions.

## Settings

The window has one row per weekday, an enable switch, start/end fields, a copy-Monday action, Revert,
and Save.

- Open state permits ordinary edits.
- Frozen/active state permits additions and containment growth, while controls that would directly
  disable a baseline block remain locked on.
- Daemon-down or malformed status makes the schedule read-only.
- Save availability is derived from semantic differences and the current edit mode.
- Revert returns to the latest authoritative baseline, never to a failed Save attempt.

An open edit that freezes immediately opens a confirmation panel. Growth-only edits always open a
confirmation panel listing changed days and the daemon-provided deadline; immediate activation says
that the screen will lock. Cancel keeps the draft. Confirm actions are one-shot and bound to the
exact authority generation, form revision, and latest action token.

The agent canonicalizes rich occurrence responses to exact day/start/end confirmation signatures.
Missing or malformed confirmation data fails closed as daemon unavailable and sends nothing.

## Warning

The agent attempts one Notification Center warning five minutes before an occurrence starts.
Notification delivery uses `osascript`; denied permission means no notification. The menu countdown
remains available. Warning attempts are occurrence-scoped and are not repeated after success.

## Uninstall

In open state, the menu opens a confirmation panel. The daemon independently rechecks authorization
and current schedule state. On acceptance it writes the response first, disables further persistence,
then best-effort removes the agent jobs, application, logs, socket, support directory, package
receipt, daemon plist, and finally its own daemon job.

UI behavior is implemented in `src/gotosleep/agent/ui.clj` and
`src/gotosleep/agent/main.clj`; coverage is in the agent UI/shell tests and the non-enforcing AppKit
smoke.
