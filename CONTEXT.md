# TaskRx

An offline-first mobile app, with a desktop Companion, that carries a person's medications and their tasks
on one timeline, so that "take the 09:00 dose" and "call the pharmacy" are the
same kind of commitment, seen in one place.

## Language

### The core aggregate

**Reminder**:
Something the user has committed to doing, together with the rule for when it
comes due. The single top-level thing this app manages.
_Avoid_: Item, Entry, Todo

**Kind**:
Whether a Reminder is a medication or a task. An axis on a Reminder, never a
separate aggregate — the two are one shape with one lifecycle.
_Avoid_: Type, Category

**Medication**:
A Reminder whose Kind is medication. Only meaningful as a Kind — there is no
Medication aggregate.
_Avoid_: Drug, Prescription, Med

**Task**:
A Reminder whose Kind is task. Everything that is not a medication.
_Avoid_: Todo, Chore, Activity

### Time

**Schedule**:
The rule that determines when a Reminder comes due. Either recurring (daily,
weekly, monthly, or every N hours), a single moment, or absent.
_Avoid_: Recurrence, Frequency, Timing

**Occurrence**:
A single moment at which a Reminder comes due, derived from its Schedule. An
Occurrence is computed, never stored — it has no identity of its own and no
record that it happened.
_Avoid_: Event, Slot, Due date

**Reminder Instance**:
The record of what the user did about one Occurrence. Distinct from the
Occurrence itself: an Occurrence is when something was due, an Instance is what
became of it.
_Avoid_: Log, Entry, Dose log, Completion

**As needed**:
A medication taken when it is wanted, not at set times (acetaminophen for a headache). Its
Schedule has no times, so it has no Occurrences: nothing about it is ever due, Missed or
caught up. What it has instead is a rule on spacing: at least so long between doses, and
optionally no more than so many in 24 hours. The app says when the next dose is allowed, and
reminds you once when that moment arrives. Not part of Adherence.
_Avoid_: PRN, Optional, Backlog (a Backlog item is a commitment with no moment; this is not a commitment)

**Pause**:
A stretch of time in which a Reminder has no Occurrences, after which it carries on as
before. It starts now or on a later day and ends on a day the person names, or when they
resume. Nothing inside it is Missed. Not Stop: a stopped Reminder is gone for good.
_Avoid_: Suspend, Hold, Snooze (a snooze defers one Occurrence)

**Occurrence override**:
One Occurrence moved to another moment, leaving the Schedule that produced it alone. A fact
about a single moment, kept on the Reminder beside its Pauses, and tasks only: a medication
Schedule is a clinical instruction. An override whose source the Schedule no longer produces
is inert, not wrong.
_Avoid_: Reschedule, Skip, Defer (a snooze defers one Occurrence without moving it)

**Stop**:
Ending a Reminder for good, e.g. a course of treatment that is over. The history is kept;
the Reminder no longer reminds, is no longer listed, and cannot be brought back.
_Avoid_: Delete, Discontinue

**Backlog**:
The Reminders that have no Schedule at all. They are real commitments with no
moment attached, so they never produce Occurrences and never appear on the
timeline until acted on.
_Avoid_: Someday list, Inbox, Unscheduled

**Grace Period**:
How long an Occurrence stays answerable after its moment passes, before it is
treated as Missed.
_Avoid_: Tolerance, Window, Buffer

**Dose**:
The quantity of a drug taken at one time — a property of a medication Reminder,
not a unit of scheduling. A scheduled taking is an Occurrence; a recorded taking
is a Reminder Instance.
_Avoid_: using "dose" to mean a scheduled or recorded taking

### What became of a Reminder Instance

**Pending**:
Due, and not yet answered. Counts neither for nor against Adherence.

**Completed**:
The user did it. The one word for both Kinds — a medication is not "taken" and a
task is not "done"; both are Completed.
_Avoid_: Taken, Done, Checked off, Finished

**Skipped**:
The user deliberately chose not to do it. An answer, not an absence.

**Missed**:
The Grace Period elapsed with no answer. The only status the user does not
choose. It is the app's guess, not a fact: a Missed Occurrence can still be
answered, so a dose taken without opening the app is recorded as Taken.
A task whose moment has passed is _shown_ as "Overdue" on the Tasks tab, but
that is a display word only: the stored status stays `missed` (or `pending`
before the sweep records it), and no rule depends on the word.
_Avoid_: Overdue, Late, Lapsed

**Snoozed**:
Deferred to a later moment. For a medication this still counts against
Adherence, because a late dose is a clinical fact; for a task it is simply a
reschedule.
_Avoid_: Postponed, Deferred

### Delivery

**Horizon**:
The bounded set of soonest Occurrences currently armed with the phone's operating
system. Bounded because the OS will hold only so many, so the Horizon is always
a selection, never the whole future. Only the phone has one; a Companion arms nothing.
_Avoid_: Queue, Schedule window, Lookahead

**Companion**:
A device that shows and answers Reminders but never delivers them: it arms no
Horizon, posts no notification and writes no sweep verdict. The desktop app is
one. It holds the person's data in memory only and needs a connection.
_Avoid_: Viewer, Second device, Client (the phone is a client too)

**Tier**:
The delivery guarantee a Reminder's notifications get, determined by Kind.
Medications get the strongest alarm the OS offers; tasks get ordinary
notifications, and cannot compete with medications for the Horizon.
_Avoid_: Priority, Level, Class

### Health

**Adherence**:
The proportion of a person's medication commitments they actually met, over a
period. A medication concept only — tasks never contribute to it.
_Avoid_: Compliance, Streak, Score

**Refill**:
The point at which a medication's remaining supply will run out, and the
Reminder raised to act before it does.
_Avoid_: Restock, Reorder

**Caregiver**:
A person other than the owner who is told about the owner's missed medications.
Distinct from anyone who might share a task: a Caregiver receives health
information, and that relationship is always opt-in and never symmetric.
_Avoid_: Collaborator, Contact, Family member

## Scope

**TaskRx is a reminder app that logs. It is not a health record.**

A feature belongs if it helps a commitment happen on time, or records honestly that it did
or did not. Two classes are out on principle, not for want of time:

- **Adjudication.** Interaction checking, dose advice, "is this safe" — the app reports what
  happened; it does not decide what should. A log that warns is making a clinical claim.
- **Clinical measurement.** Vitals, symptoms, mood, side effects — accumulating readings
  turns the store into a record and the product into someone else's category.

The log is the feature: what was due, what was answered, when. Never a score, a grade or a
compliance percentage presented as a judgement — Adherence is a count of what happened, not
an assessment of the person.
