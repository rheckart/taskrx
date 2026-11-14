# A move is an override on one occurrence, not a schedule edit

A notice arrives saying a commitment's time has changed: "Collection is now Wednesday at
09:00." A task in TaskRx is on Tuesday at 09:00, and it is that *one* Tuesday the person
means. The Schedule says "every Tuesday" and must stay that way — the next one is due as
usual.

## The shape

`Reminder.overrides` is a list of `OccurrenceOverride(from, to, noticeId?)`. `from` is the
moment the Schedule produced, `to` is the moment the occurrence now falls on, and `noticeId`
names the Notice that asked for it when one did. It is the same single-point shape as
`pauses` (ADR 0009): a fact about a moment, carried on the Reminder so that nothing holding
a Reminder has to learn a second lookup.

**Rewriting the Schedule was rejected.** Turning `weekly(Tuesday)` into
`weekly(Wednesday)` moves every future occurrence, not this one. The person said Wednesday
*this week*; next week is still Tuesday.

**Raising a one-off task was rejected.** Skipping this occurrence and adding another loses
the link to the commitment's history: the Tuesday moment would read as Missed, adherence
would count a miss that never happened, and undoing or editing the source task would not
touch the copy.

**A separate overrides table was rejected for v1.** The list rides on the Reminder, so it is
written, synced, pulled and migrated by exactly the path `pauses` already uses.

## Where it applies

Inside `nextOccurrences`, beside `pauses`. Every surface takes its moments from there — the
Horizon, the timeline, the Missed sweep, Catch up, the refill projection, the undo check —
so none can disagree about when an occurrence is. Concretely: base moments whose `fireAt` is
an override source are dropped, each override's `to` is emitted separately when it is after
its source and the source is still a genuine occurrence, overrides whose source or target is
paused are skipped, and the result is sorted, de-duplicated on `fireAt` and cut to the
requested count.

**An override is not a pause.** A pause is a window that suppresses moments; an override
moves one moment and keeps it visible, answered and counted.

## What may not move

- **Medications never move.** A medication Schedule is a clinical instruction. Moving a dose
  because something outside the app said a time is the app acting on health information it
  cannot verify. `moveRefusal` enforces that in `:core` where every caller already goes.
- **An answered occurrence never moves.** An answer is history and is not rewritten.
- **An opened occurrence never moves.** A `pending` or `snoozed` Instance had its time
  written to the server when it was minted, and no path updates it, so a move would leave
  that row claiming the old moment.
- **An occurrence past its Grace Period never moves** (ADR 0005): late is not the same as
  answered, but it is no longer a moment to defer.
- **A pause wins over a move** covering either end.

The refusals are ordered source-first. A dialog opens on the moment the occurrence already
has, where the target is not yet a move; reporting "pick a moment after the one it is on"
there would hide the real reason behind a field the person has not touched.

## Retention and inertness

Overrides are kept indefinitely — a handful a year, and each one explains why a moment was
not where the Schedule put it. An override whose `from` the Schedule no longer produces,
because the person edited the rule, simply never fires again. It is inert, not wrong, and
the edit form lists moves so the list never becomes a mystery, with a way to remove one.

## Sync

Convex gains `overrides: v.optional(v.array(...))`, optional so existing documents stay
valid. The same JSON is the Room column, the sync payload and the server field
(`overridesToJson` / `overridesFromJson` in `:core`). The Convex validator cannot make a
field depend on `kind`, so the Kind guard is checked in `reminders.create` and
`reminders.update` instead.

Like pauses, a list is always sent whole. Removing the last move sends `[]`, a value, not a
null, so it clears the server's copy; an edit that did not touch moves sends none.

The device database moves to version 3 with a real `Migration` (`ALTER TABLE ... ADD
COLUMN`) and no destructive fallback: the phone holds real answers and unsynced changes.

**Deploy order:** the Convex backend must be deployed before an app that sends `overrides`
is installed, for the same reason as pauses.
