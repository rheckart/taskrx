# Medications and tasks use different notification mechanisms

The Horizon is a bounded selection of the soonest Occurrences, ordered purely by
time. Once tasks share it, a dozen routine task reminders can fill it and leave
an evening insulin dose unarmed — silently, with no error. So delivery is split
by Kind: medications keep `SET_ALARM_CLOCK` and the global exact-alarm Horizon
that Phase 1 verified on a physical Android 17 device, and tasks use ordinary
notification triggers on a separate budget. A task cannot displace a dose by
construction rather than by policy.

## Considered Options

Reserving a fixed number of Horizon slots for medications was rejected because
tasks would still consume exact-alarm budget and the permanent status-bar alarm
glyph, for reminders that do not warrant either.

Making the dormant `Priority` field load-bearing — sorting by priority, then
time — was rejected because it produces its own wrong answer: a critical dose
four days out would displace an ordinary one arriving in ten minutes.

## Consequences

There are two scheduler paths to maintain and to device-verify, not one. In
exchange the medication path is left exactly as Phase 1 proved it, so that
evidence stays valid.

This also protects a Play-review position recorded in `docs/architecture.md`:
`USE_EXACT_ALARM` is deliberately not declared, because Google restricts it to
apps whose core function is an alarm clock or calendar. Firing alarm-clock
primitives for "walk the dog" would make that argument considerably harder to
defend.

`Priority` remains stored, validated, and parsed from words like "urgent" — and
read by nothing. It either earns a job or it should be removed.
