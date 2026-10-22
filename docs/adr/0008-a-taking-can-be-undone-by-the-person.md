# A taking can be undone, and only by the person

Until now a `completed` Instance was final everywhere. On the device a pull never
overwrote it (`shouldApplyRemoteReminderInstance`); on the server every mutation but
`markCompleted` returned early when the row was `completed` (`isCompleted`); and the supply
rule said "there is no refund to make". The point was `docs/specs/fused-timeline.md` story
44: a taken dose stays taken, so a stale copy of the row arriving from another phone can
never turn a recorded dose into a miss.

That rule is kept. What changes is that the person can now take their own answer back: a
take, a skip or a snooze, from the confirmation card or from the row on the timeline. An
answer is a button press, and a mis-tap on "Taken" for a dose that was not taken is the
worse error, since it silently scores a miss as a success and spends a pill.

**One new way out of `completed`, and no other.** The server gains `reminderInstances:reopen`,
which sets the row to `pending`, clears `completedAt` and `snoozedUntil`, keeps `snoozeCount`
and `notes`, and stamps `updatedAt` with the server's clock. `markSkipped`, `markMissed`,
`snooze` and the replayed `create` still refuse a `completed` row exactly as before. A stale
queue draining from another phone is therefore still harmless: it can only send those four.

## Considered Options

**Allow any write to replace a taking, last-write-wins.** Rejected: it is the failure the
rule exists for. A phone that was offline since breakfast would turn lunchtime's recorded
dose into a skip.

**Offer Undo for skip and snooze only, and keep a taking final.** This was the
recommendation, and it needed a new server function all the same, because nothing returned
any answered row to `pending`. It was set aside by the person who owns the app, who wanted
a taking to be undoable too.

**Delete the row.** Rejected: Instances are append-only history (a "deletion" is a status
change, and the sync queue carries it). An undo is a status change back to the state the
row was minted in.

## The device's conflict rule

`shouldApplyRemoteReminderInstance` gains two clauses:

- **A row with a change this device has not pushed is never overwritten by a pull.** This is
  what stops the undo being lost. Take, sync, then undo offline: the next pull still says
  `completed`, "a remote completion always applies" would put it back, and the queued
  `reopen` would later leave this phone disagreeing with the server for good. "Not pushed"
  means any entry for the row in the sync queue, a dead-lettered one included: a change the
  server refused is still the newest thing the person did and is listed in Sync problems
  until they decide.
- **A local `completed` is replaced only by a remote `pending` with a newer `updatedAt`.**
  That can only be a `reopen` made on another phone. A stale skip, snooze, miss or older
  completion still cannot erase a taking; a tie stays with the local row.

One known limit: `updatedAt` on the phone is the phone's clock and on a `reopen` it is the
server's. A phone whose clock ran minutes fast, with a taking recorded in those minutes and
undone on another phone straight away, does not see the undo until the next write to the
row. Making this exact would mean a server-assigned version, which the rest of the sync
layer does not have either.

## What an undo does

- Returns the Instance to `pending`, and re-reads the schedule so a moment still ahead is
  armed again and a deferred reminder for it is cancelled (only that moment's).
- Gives a taken dose's pills back, `pillsPerDose` or one, in the same transaction
  (`supplyAfterDose`: Completed to anything else). Only a local write refunds, as only a
  local write spends: an undo pulled from the server touches nothing, because the other
  phone refunded it. The zero clamp makes this inexact by up to one dose (a take that found
  half a pill left spent half and is refunded a whole one); the person can set the count.
- If the moment's Grace Period (ADR 0005) is already over, the next sweep records it as
  `missed` straight away. The confirmation says so rather than hiding it.

## Where Undo is not offered

- **A moment the schedule does not predict** (an extra dose, a Backlog completion, an
  Instance left from a schedule that has changed). Reopened, it would be a pending row nobody
  was ever due to answer, and the sweep would record a miss that never was.
- **A moment that is already open again** (issue #21: the answer screen reopened it and
  minted a second pending row). Reopening would leave two open rows at one pairing and the
  sweep records a miss for each.
- A `pending` or `missed` row: nothing was answered. A Missed moment is answered, not undone.
- The notification shade. It cannot show the result.

## Consequences

- **Deploy order matters.** The server must have `reopen` before an app that sends it is in
  anyone's hands. An older server answers an undo with an error; the worker retries it to
  the dead-letter threshold and the row shows in Sync problems.
- A taking is no longer terminal for the person, so a report that counted "completed
  forever" has to read the current status. Nothing in the app does.
- A replayed `create` that carries `completed` after a `reopen` would complete the row
  again. The queue drains in order, so a `create` always runs before the answers made after
  it, and nothing replays one. Pinned in the push tests (create, markCompleted, reopen).
