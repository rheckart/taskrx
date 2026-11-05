# An as-needed medication has no Occurrences

Some medications are not taken at set times. Acetaminophen for a headache is taken when the
headache comes, with a rule on the label: at least four hours apart, no more than five in a day.
The person wants the app to tell them when the next dose is allowed, and to offer it then, with
a way to say no.

## The decision

A new Schedule variant, `AsNeededTimes(minGapMinutes, maxPer24h?)`, produces **no Occurrences**,
the same as a Backlog item. "When the next dose is allowed" is a fact about spacing, derived from
what was taken; it is not a moment that was due.

## Why not an Occurrence

**A follow-up Occurrence after each dose was rejected.** The natural reading is "after Tylenol,
create the next reminder four hours on". That moment would be an Occurrence, so everything built
on Occurrences would apply to it: the Missed sweep would mark it Missed when the person did not
want another dose, Catch up would ask about it, Adherence would count it against them, and Undo
would have rules for it. Each of those is wrong for a dose nobody was due to take. Keeping the
variant free of Occurrences keeps it out of all of them without any of them knowing about it,
which is the reasoning ADR 0009 used for Pause.

**A stored pending Instance was rejected** for the same reason, plus CONTEXT.md: an Occurrence
is computed, never stored.

## What it does instead

- **When a dose is allowed** is `asNeededStatus` in `:core`, a pure function of the schedule, the
  moments doses were taken and `now`. It is the later of "last dose plus the gap" and, once the
  limit is reached, "24 hours after the dose that has to leave the window to get back under it".
  A dose exactly 24 hours old is out of the window. Takings are Completed Instances' `completedAt`,
  so the answer is the same whichever way a dose was recorded: the Today card, Log a dose, the
  notification's Take, or a time changed afterwards. If a take is undone it stops counting (ADR 0008). Nothing in the app offers Undo for these rows yet: they are unscheduled doses, which Undo does not reopen into a miss that never was, and that rule has not been extended to cover them.
- **Today** shows a card per as-needed medication at the top of the Medications tab, outside the
  day's list, with a countdown that updates each minute. It does not depend on the day's rows, so a
  day with only this medication does not read as empty. Taking one before it is allowed asks first
  and says which rule it would break; it never refuses, because a dose already swallowed must be
  recordable.
- **The notification** is a Medication Tier spec with id `ready-<reminderId>-<readyAt>`, derived
  like any other by `desiredNotifications`, so the diff, reboot re-arm and Stop all work unchanged
  (`belongsToReminder` knows the prefix). **Only while `readyAt` is still ahead.** Once it has
  passed nothing is armed, so an ignored prompt is not a debt and is not re-armed at every
  reschedule. Taking another dose moves `readyAt`, which replaces the prompt.
- **Its buttons** are Take and Skip. Take records a dose now. **Skip writes nothing**: a Skipped
  medication Instance counts in Adherence's denominator, and this prompt was not due. The next
  prompt comes with the next dose. There is no Snooze: the wait it announces is over, and nothing
  is due that could be deferred. It joins no batch, and a tap opens Today, because there is no
  Occurrence for an answer screen to find.
- **Adherence leaves these medications out** (`AdherenceReader`). Adherence is the share of
  commitments met, and this has none. Counting its takings would add one to both sides of the
  figure and pull it toward 100%; its absence is not a miss. Pause is not offered for it, since it
  has no times to hold back.

## Sync

Convex's `scheduleTimesValidator` gains `{type: "asNeeded", minGapMinutes, maxPer24h?}`. The
`schedule` column on the phone is JSON text, so the device database needs no migration.

A build that does not know the variant reads a schedule it cannot parse as `NoneTimes`
(`ServerDocs`, `Codecs`), which puts the medication in that build's Backlog. It does not crash or
stop syncing; it shows up in the wrong place until that build is updated, and an edit made from
it would overwrite the schedule with "none". This is a one-person app on one phone, so the rule
is: update the app before editing the medication anywhere else.

**Deploy order:** deploy the Convex backend before installing an app that can create one. Convex
rejects a schedule it does not know, so the old server would refuse the push five times and
dead-letter it.

## Not done

- The phrase parser ("Add by typing") does not read "as needed" or "PRN". A phrase such as
  "Tylenol every 4 hours" still becomes a fixed Interval schedule, which reminds on the clock and
  can be Missed. Choose **As needed** on the form's schedule type, or in a later change teach the
  parser to produce it.
