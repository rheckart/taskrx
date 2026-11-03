# A pause is a window in time, not a Stop

A person needs two ways to go quiet about a medication. One course of ten days is over and
they never want it again. Surgery is coming, so a blood thinner is held until afterwards and
then picked up again. Stop (`active = false`, a `delete` to the server) already covers the
first. It cannot cover the second: the server hides a deleted Reminder on every device, no
screen brings it back, and recreating it would lose its history, its supply count and its
refill reminder.

So a Reminder can be **paused**: it keeps existing, and it has no Occurrences inside a
window. `Reminder.pauses` is a list of `PauseWindow(from, until?)`. A moment is paused when
`from <= moment < until`; a window with no `until` runs until the person resumes. `active`
is untouched, so everything that asks "is this live" (the edit screen, the Reminders list)
still answers yes.

## Why a window, and why a list

**A flag, cleared on resume, was rejected.** The Missed sweep looks back seven days
(ADR 0005). Clear the flag and every paused dose in that lookback is an unanswered one, so a
person back from surgery is told they missed a week of doses and their Adherence drops.
Resuming therefore does not delete the window: it ends it at that moment, and the moments it
covered stay suppressed.

**A single window was rejected.** Pause, resume, pause again would overwrite the first
window, and its moments would become Missed the same way. Windows are kept as a list. At most
one is unfinished at a time (`addPause` refuses a second); resuming a window that has not
started removes it, since nothing was ever paused.

**The rule lives in `nextOccurrences`.** The Horizon, the timeline, the Missed sweep, the
catch-up, the refill projection and the undo check all take their moments from it, so none
can disagree about whether a moment exists. It asks the Schedule for as many moments as are
still missing, keeps the unpaused ones, and goes again from the last moment seen. Filtering
one counted batch would return too few, and a pause longer than a Schedule's look-ahead (60
days daily, 56 weekly) would return none although doses resume after it. When the next
moment would land inside a pause with an end, the walk jumps to the end.

An interval schedule keeps its grid: after a pause it resumes at the next slot of "every N
hours from its start", not N hours after the person resumed.

## What a pause does not do

- Refill: the supply is not drawn down by moments that do not exist, so the run-out date
  moves out by the length of the pause. An open pause gives no run-out date at all.
- A dose recorded by hand inside a pause (+ Dose) is still recorded, and counts as Completed.
- A pause cannot start in the past: the dialog starts it now or on a later day.
- A dose snoozed before the pause began, and never answered, still becomes Missed once its
  snooze runs out. The moment itself was before the window, so the sweep is right to judge it.
  A snooze alarm already armed when a pause starts now is cancelled; one for a pause that
  starts later is left to ring.
- Only medications offer Pause. The mechanism is on the Reminder and would work for a Task.

## Sync

Convex gains `pauses: v.optional(v.array({ from, until? }))`, optional so existing documents
stay valid with no migration. The same JSON is the Room column, the queue payload and the
server field (`pausesToJson` / `pausesFromJson` in `:core`).

Clearing does not round-trip for most fields (a null is omitted, which the server reads as
"leave alone"). Pauses avoid that on purpose: a list is always sent whole, and cancelling a
pause sends `[]`, a value, not a null. An edit that did not touch pauses sends none, so a
rename on one phone cannot overwrite windows set on another.

The device database moves to version 2 with a real `Migration` (`ALTER TABLE ... ADD COLUMN`).
There is no destructive fallback: the phone holds real doses and changes that have not
synced.

**Deploy order:** the Convex backend must be deployed before an app that sends `pauses` is
installed. Convex rejects arguments it does not know, so the old server would refuse the
update five times and dead-letter it.
