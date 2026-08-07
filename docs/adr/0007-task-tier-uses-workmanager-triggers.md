# The Task Tier delivers through WorkManager, not AlarmManager

ADR 0003 split delivery by Kind and left the task Tier's mechanism open; the
spec records it as a question to settle before the first Task-Tier ticket. It is
settled here: a task is armed with `alarmManager: false` on a notify-kit
timestamp trigger, which schedules a `OneTimeWorkRequest` through **WorkManager**
rather than an alarm through **AlarmManager**. Medications keep
`SET_ALARM_CLOCK` and are untouched.

The two Tiers therefore do not merely use different parameters of one API. They
use different operating-system subsystems, which is what makes ADR 0003's "by
construction rather than by policy" true at the layer below the app: a task
consumes a work request, a dose consumes an exact-alarm slot, and no quantity of
the former can exhaust the latter. There is no ordering, no budget arithmetic,
and no priority comparison in which a task can leave a dose unarmed, because
nothing the two contend for is shared.

Three consequences follow that are worth having chosen rather than discovered:

- **A task needs no `SCHEDULE_EXACT_ALARM`.** Someone who has denied that
  permission gets degraded dose alarms and full-fidelity task reminders, which
  is the right way round.
- **No status-bar alarm-clock glyph**, which `SET_ALARM_CLOCK` renders for as
  long as any trigger is pending. `docs/architecture.md` argues that glyph is
  acceptable — arguably a feature — for a medication reminder. It is neither for
  "walk the dog".
- **This is also the Play-review position.** ADR 0003 records that
  `USE_EXACT_ALARM` is deliberately not declared because Google restricts it to
  apps whose core function is an alarm clock or calendar. Firing alarm-clock
  primitives for ordinary tasks would have made that argument considerably
  harder to defend; firing work requests for them does not arise.

## Considered Options

**An inexact alarm** — `AlarmType.SET` or `SET_AND_ALLOW_WHILE_IDLE` — was the
obvious alternative and is the one this rejects. It would deliver a task about as
well, but it keeps both Tiers inside AlarmManager, where they share a budget and
where the difference between them is one enum value in one expression. That is a
policy again: correct today, and one careless edit from the failure ADR 0003
exists to prevent. The subsystem boundary cannot be crossed by a typo.

**The `alarmManager` key omitted entirely** was considered and is a trap rather
than an option, which is why it is recorded here. It reads as "no alarm manager"
and means the opposite: notify-kit's validator defaults a timestamp trigger *to*
AlarmManager with `SET_EXACT_AND_ALLOW_WHILE_IDLE`, so omitting the key gives
every task an exact, Doze-bypassing alarm on the exact-alarm budget — silently,
with no error. The explicit `false` is what makes the validator drop the key from
the bundle, which is what the native side reads as WorkManager, because
`TimestampTriggerModel` decides on `containsKey` rather than on the value. The
library's own TypeScript comment on that field says timestamp triggers use
AlarmManager by default; the older upstream documentation says WorkManager. Both
were checked against the shipped source rather than believed.

## Consequences

**A task reminder is approximate, and that is the trade.** WorkManager batches
work and defers it in Doze until a maintenance window, so a task may arrive some
minutes after its time rather than at the second. For a task that is right; for a
dose it would not be, and a dose does not get it.

**The two budgets are counted separately.** The medication Horizon stays at 64
and the task Horizon is 32 — not a share of the 64, and raising it cannot cost a
dose its alarm. 32 is not an OS limit, since WorkManager imposes none; it bounds
the diff pass and notify-kit's trigger table.

**The id namespaces have to stay disjoint, and that is now load-bearing.**
`getTriggerNotificationIds()` returns every pending trigger from both subsystems,
because notify-kit records them in one table. A reschedule pass that knew about
only one Tier would compute the other's triggers as stale and cancel them — the
medication failure of ADR 0003 arriving from the opposite direction, and one app
foreground away. Task triggers are prefixed `task-`, both Tiers are diffed in a
single pass, and `scheduler.test.ts` pins that in both directions.

**iOS will not inherit this.** It has no WorkManager, and its hard cap of 64
pending local notifications is per app across both Tiers — so an iOS twin has to
split one budget rather than hold two, and cannot get the structural guarantee
for free. That is a decision for the iOS ticket, and it should not be made by
copying these numbers.

**Two mechanisms now need device verification, not one.** Harness cases 10 and
11 cover them: that a task notifies at its time and never registers as the
device's next alarm clock, and that a completely full task budget still leaves
the dose armed and holding that registration.
