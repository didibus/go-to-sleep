# Schedule and rules

## Model

The schedule is a seven-key map from `:mon` through `:sun`. Each day is either disabled (`nil`) or
has one `{:start "HH:MM" :end "HH:MM"}` block. A block belongs to its start day and may cross
midnight. Equal endpoints are invalid, and wall-clock duration must be at least 15 minutes.

Occurrences are resolved through the effective IANA time zone. A nonexistent DST local time moves
to the first valid instant; an ambiguous local time uses the earlier instant. If DST resolution
would place an end before its start, the occurrence is clamped to the minimum duration.

## States

- `:open`: no occurrence is within its freeze window.
- `:frozen`: an occurrence starts within eight hours but is not active.
- `:active`: the current trusted instant lies within an occurrence.

An occurrence is frozen from exactly eight hours before its start until its end.

## Schedule edits

When the authoritative state is open, any valid replacement schedule is allowed if the complete
window rule remains valid. If an open edit would create a freeze immediately, the daemon requires an
exact confirmation naming the affected occurrence signatures.

While any occurrence is frozen or active, the entire weekly schedule is growth-only:

- an empty weekday may gain a block;
- an existing block may keep the same or move to an earlier start;
- an existing block may keep the same or move to a later end;
- every changed existing block must strictly grow and fully contain its previous wall-clock span;
- every currently frozen occurrence must also remain contained in instant time after DST resolution.

An identical schedule, shortening, shifting without containment, disabling, or deleting any existing
block is rejected. A multi-day request is atomic: one prohibited change rejects the whole candidate.
The daemon enforces these rules independently of the UI.

Every accepted growth-only edit requires an exact daemon-issued token binding the baseline,
candidate, zone, current frozen signatures, authority deadline, candidate deadline, and whether the
change activates immediately. Tokens are invalid after any bound authority changes.

## Window rule

Across the complete occurrence horizon, one block's end must be at least nine hours before the next
block's start. This leaves one unfrozen hour before the next eight-hour freeze window. The rule also
applies to newly added blocks and prospective time-zone adoption.

## Time zone

The daemon follows `/etc/localtime` when the prospective zone keeps every currently frozen occurrence
contained and preserves the complete window rule. Otherwise it retains the old effective zone and
reports the system zone as pending. Zone adoption does not modify the weekly wall-clock schedule.

## Trusted time

The daemon combines persisted wall/monotonic anchors, boot-session identity, raw monotonic readings,
and periodic network-time observations. A backwards or implausible wall-clock step makes the clock
suspect; trusted time continues from the monotonic anchor unless independent network-time evidence
confirms the step.
