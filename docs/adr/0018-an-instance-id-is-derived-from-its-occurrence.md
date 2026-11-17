# An Instance's id is derived from its Occurrence

Until the desktop Companion (ADR 0016), the phone was the only device that created a
Reminder Instance, and its sweep and answers are serialized locally. With two writers, the
09:00 dose can be answered on the desktop while the phone answers it, or sweeps it Missed,
before either has synced. Each device mints a random UUID, and `reminderInstances.create`
de-duplicates only on `localId`, so Convex would hold **two Instances for one Occurrence**.
That double-counts Adherence and spends the supply twice.

So a new Instance's id is **derived from `(reminderId, scheduledTime)`** (a name-based UUID,
v5, under a fixed TaskRx namespace) on both devices. `reminderId` here is the Reminder's
**device UUID**, the `localId` the server stores and the phone's Room key, never the Convex
document id. A Reminder created on the desktop gets a random UUID `localId` in the same way. Two devices answering one moment write
the same `localId`, and the server's existing localId merge folds them into one document.
On the phone the Room primary key lines up with what it pulls.

## Considered Options

**The server merging on `(reminderId, scheduledTime)` was rejected.** It needs a new index
and a fold inside `create`. The phone would then hold a local row under its own UUID that
the server has merged into another device's document, which means one Convex id for two
local rows and a reconciliation pass to repair it.

**Only the phone minting was rejected.** The desktop could then answer only what the phone
had already created, so a dose would be unanswerable from the laptop while the phone is
asleep. That is most of the reason to have a desktop.

## Consequences

- Rows that already exist keep their random ids. Only new mints are derived, so **every
  writer looks up an existing Instance for the Occurrence first** (the phone's
  `findAnyForOccurrence`, the server's `listForReminder` on the desktop) and updates it
  rather than minting.
- What the merge does is today's `create` upsert (`reminderInstances.ts`): `completed` is
  terminal and keeps its `completedAt`. Any other status is **last arrival wins**, with no
  `updatedAt` comparison. So a desktop Skip followed by a late phone sweep becomes Missed.
  Decide whether the upsert should prefer the person's answer over a sweep verdict.
- Creating an Instance whose id already exists locally becomes an update governed by
  `InstanceLifecycle` and `Conflict.kt`, not a failed insert.
- The derivation is a rule, so it lives in `:core` and is in the golden fixtures
  (ADR 0017), so the Rust port derives the same id byte for byte.

## Open questions

- `ReminderInstancesRepository.allForOccurrence` implies one Occurrence can hold more than
  one Instance today. Before this lands, establish when that happens (the answer screen
  minting a second pending row, snooze, undo per ADR 0008) and whether each case should
  become an update of the one Instance.
- **Supply is a counter, and counters do not merge.** A completion spends pills through an
  ordinary Reminder update (`spendSupply`), and Reminders are last-write-wins. Two devices
  completing *different* doses before syncing would each write `remaining - 1` from the same
  starting value, and one spend would be lost. One shared id prevents the double spend for
  one dose, not this. Supply likely has to be spent by the server inside the completing
  mutation.
- A dose recorded with **+ Dose** and a Backlog completion carry a chosen or completion
  moment, not one the Schedule produced. Two such records at the same millisecond would
  merge, which is acceptable, but confirm it.
