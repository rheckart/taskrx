# TaskRx

An offline-first mobile app that carries a person's medications and their tasks
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
weekly, or every N hours), a single moment, or absent.
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
choose.
_Avoid_: Overdue, Late, Lapsed

**Snoozed**:
Deferred to a later moment. For a medication this still counts against
Adherence, because a late dose is a clinical fact; for a task it is simply a
reschedule.
_Avoid_: Postponed, Deferred

### Delivery

**Horizon**:
The bounded set of soonest Occurrences currently armed with the operating
system. Bounded because the OS will hold only so many, so the Horizon is always
a selection, never the whole future.
_Avoid_: Queue, Schedule window, Lookahead

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
