# A `once` Schedule carries an instant; all-day is not a variant yet

`docs/specs/fused-timeline.md` leaves one question open against the `once`
Schedule: whether it supports a date with no clock time, so that "renew
insurance Tuesday" can be recorded as the all-day thing it is.

It does not. `once` is `{ type: "once", at: <unix ms> }` — a single instant,
always with a time. An all-day Reminder is out of scope for now, and when it
arrives it should be its own Schedule variant rather than a nullable field on
this one.

## Considered Options

**A nullable time inside `once`** was rejected because every consumer downstream
is keyed on an instant, and each would have to invent its own answer for the
null. Occurrence generation returns a `fireAt`; the notification Horizon sorts
and truncates on it; the timeline sorts on it and pairs a stored Instance to a
predicted Occurrence by it; `scheduledTime` on a Reminder Instance is
non-nullable and carries four time-ordered indexes (ADR 0002 declined to make it
nullable for exactly this reason). Five consumers inventing five different
midnights is a worse outcome than not offering the feature.

**A distinct `allDay` variant now** was rejected as premature rather than wrong.
An all-day commitment differs in *behaviour*, not just in precision: it should
not arm an alarm at a moment, it should sit at the head of a day rather than at a
position within it, and it has no Grace Period boundary in the sense ADR 0005
gives that term. Those are timeline and delivery decisions, and none of them is
needed to close the two things this ticket exists for — a Reminder that happens
exactly once, and a Reminder with no time at all.

## Consequences

A person capturing "renew insurance Tuesday" gets a `once` Schedule at some
clock time on Tuesday — a default the entry surface picks, not something they
asked for. That is lossy: the app will remind them at a moment they did not
choose. The honest alternative available today is the Backlog, which is what
`none` is for: a real commitment with no moment attached.

Adding `allDay` later is additive. Both the shared union and the Convex
validator are closed unions widened together, so a new variant is a compile
error at every consumer that has to decide something about it — which is the
property that makes deferring this safe rather than a trap.
