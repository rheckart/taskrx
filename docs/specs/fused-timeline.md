# Spec: The fused timeline — tasks and medications on one surface

> Status: ready-for-agent. Awaiting publication to Forgejo; written to a file
> because `forgejo-mcp` was unavailable in the authoring session.

## Problem Statement

A person managing a long-term condition is running two systems at once. Their
medications live in one app that is good at firing an alarm at 09:00 and useless
at "call the pharmacy before Friday". Their commitments live in another app that
is good at "call the pharmacy" and has no idea that missing a dose matters more
than missing a chore.

Neither app can answer the only question that person actually asks in the
morning: **what do I need to do today, in order.** They have to hold the merge
in their head, every day, and the cost of getting it wrong is not a missed
chore.

This app already manages medications. It cannot express a Task at all — every
screen, every write path, and every notification assumes a drug. The unified
domain model to fix that was written and tested months ago, and nothing uses it.

## Solution

One timeline. A Reminder is something the person has committed to doing, and its
Kind — medication or task — decides how it behaves, not whether it belongs.
Doses and tasks appear together in one chronological list, so "take the 09:00
dose, then call the pharmacy at 10:00" is a single thing to read.

Where the two Kinds genuinely differ, they stay apart. A medication is armed with
the strongest alarm the operating system offers and counts toward Adherence; a
task gets an ordinary notification and does not. A person adds either one by
typing a phrase — "atorvastatin 20mg every morning" or "call pharmacy friday
2pm" — and the app works out which it is.

Unified where the person looks. Separate where they act.

## User Stories

### Seeing the day

1. As someone taking daily medication, I want my doses and my tasks in one
   chronological list, so that I can see my whole day without merging two apps
   in my head.
2. As someone taking daily medication, I want a dose to be visually
   distinguishable from a task at a glance, so that I never confuse the two when
   scanning quickly.
3. As someone with a busy morning, I want the timeline to show what is next
   rather than what is first, so that a glance tells me what to do now.
4. As someone who has already dealt with an item, I want it to remain visible as
   handled rather than vanish, so that I can see I have done it.
5. As someone who missed something, I want it to stay on the timeline as Missed
   rather than silently disappear, so that I know it happened.
6. As someone reviewing yesterday, I want to look back at a previous day's
   timeline, so that I can see what I did and did not do.
7. As someone with nothing scheduled, I want the timeline to say so plainly
   rather than show an ambiguous blank, so that I trust the app is working.

### Adding things

8. As someone adding a medication, I want to type "atorvastatin 20mg every
   morning with food" and have it understood, so that I do not fill in six form
   fields.
9. As someone adding a task, I want to type "call pharmacy friday 2pm" and have
   it understood, so that capture is as fast as thinking.
10. As someone typing a phrase, I want the app to infer whether it is a
    medication or a task, so that I do not have to categorise before I capture.
11. As someone typing a phrase, I want to see what the app understood before it
    saves, so that I can correct a misreading rather than discover it at 09:00.
12. As someone whose phrase was partly misunderstood, I want to fix the parts it
    got wrong without retyping the whole thing, so that a near-miss is not a
    restart.
13. As someone whose phrase could not be parsed at all, I want to fall back to
    entering it directly, so that an unusual phrasing never blocks me.
14. As someone adding a medication, I want to search for the drug by name and
    pick the real one, so that my records use a recognised medication.
15. As someone adding a recurring task, I want to say which days of the week it
    falls on, so that a weekday-only commitment does not nag me on Sunday.
16. As someone adding a one-off task, I want to give it a single date and time,
    so that it appears once and then is done.
17. As someone capturing something with no deadline, I want to add it without a
    time at all, so that I do not have to invent a fake due date to record an
    intention.

### The Backlog

18. As someone with undated intentions, I want them in a Backlog separate from
    my day, so that they do not clutter a timeline they have no place on.
19. As someone working through the Backlog, I want to complete an item directly
    from it, so that acting on it does not require scheduling it first.
20. As someone whose Backlog item has become urgent, I want to give it a time and
    move it onto the timeline, so that the app reminds me about it.

### Acting on things

21. As someone taking a dose, I want to record that I took it in one tap, so
    that logging never becomes the reason I stop logging.
22. As someone who deliberately skipped a dose, I want to record that as a
    decision rather than a failure, so that my history reflects what actually
    happened.
23. As someone who cannot act right now, I want to defer an item to later, so
    that the reminder returns instead of being lost.
24. As someone completing a task, I want the same one-tap interaction as a dose,
    so that the timeline behaves consistently.
25. As someone acting on a notification, I want to record the outcome from the
    notification itself, so that I do not have to open the app.
26. As someone who forgot to log a dose at the time, I want to record it after
    the fact, so that my history stays accurate.

### Being reminded

27. As someone whose dose is time-critical, I want its alarm to fire exactly on
    time even when the phone is idle or the app is closed, so that I can rely on
    it.
28. As someone with many task reminders, I want them never to prevent a dose
    alarm from being set, so that my productivity habits cannot endanger my
    health.
29. As someone with an ordinary task, I want its reminder to feel like a
    notification rather than an alarm, so that my phone does not treat "walk the
    dog" like a medical event.
30. As someone who restarted their phone, I want my reminders to survive the
    reboot, so that a restart is not a silent gap in my treatment.
31. As someone who has denied the exact-alarm permission, I want reminders to
    still arrive approximately rather than not at all, so that a permission
    choice does not silently disable the app.
32. As someone with no signal, I want every reminder to fire anyway, so that
    connectivity is never a precondition for my medication.

### Missed and overdue

33. As someone who did not respond to a dose, I want it recorded as Missed after
    a reasonable Grace Period, so that a late log is not punished as a failure.
34. As someone who has an overdue task, I want it to remain actionable rather
    than closed off, so that being late does not stop me finishing it.
35. As someone returning after a long absence, I want the app to open promptly
    rather than grinding through months of history, so that coming back is not
    punished.

### Adherence and refills

36. As someone tracking my treatment, I want to see what proportion of my doses
    I actually took, so that I know whether I am managing my condition.
37. As someone tracking my treatment, I want my tasks excluded from that figure,
    so that a productive week cannot disguise a bad medication week.
38. As someone tracking my treatment, I want my Adherence to count doses I never
    responded to, so that the number is honest rather than flattering.
39. As someone reviewing a period, I want a day-by-day breakdown, so that I can
    see patterns rather than a single blended number.
40. As someone taking a finite supply, I want to know how many days I have left,
    so that I can reorder before I run out.
41. As someone about to run out, I want a Refill Reminder raised ahead of time,
    so that I act while a pharmacy is still open.

### Continuity

42. As someone with more than one device, I want everything I record to appear on
    both, so that where I logged something does not matter.
43. As someone who was offline, I want my changes to reach the server once I
    reconnect, so that nothing is lost.
44. As someone whose dose was recorded on one device, I want that record never
    overwritten by stale data from another, so that a taken dose stays taken.

## Implementation Decisions

These follow the eleven decisions taken in the design session. The four
hard-to-reverse ones are recorded as ADRs in the repository's ADR directory and
are binding on this work.

### Domain model

- **A single Reminder aggregate** carries both Kinds. Kind is a discriminator,
  never a separate aggregate. There is no Medication type and no Task type.
- **Schedule gains two variants.** In addition to daily, weekly, and interval, a
  Schedule may be *once* (a single moment) or *none* (no moments at all). The
  *none* variant is what makes the Backlog explicit rather than an accident of
  an empty daily schedule. Both the shared domain union and the backend
  validator are closed unions and must be widened together.
- **Reminder Instance statuses become Kind-neutral**: pending, completed,
  snoozed, skipped, missed. The completion timestamp is renamed to match. See
  ADR 0001. This is a mechanical, compiler-guided rename across the shared
  package, the backend validators and functions, the on-device schema, and the
  existing test suites.
- **A Backlog Reminder's Instance is minted at completion**, with its scheduled
  time set to the completion time. The scheduled time therefore means "the
  instant this Instance occupied on the timeline". See ADR 0002. Scheduled time
  stays non-null so every time-ordered index remains valid.

### Sequencing — the cutover comes first

The unified tables are currently *derived*: the app writes the legacy
medication and dose-log tables, mirror modules project those into the unified
tables, and the screens read the unified side. A Task has no legacy
representation, so it cannot flow through that pipeline at all. The cutover is
therefore a hard prerequisite, not a cleanup.

- The unified tables become the write path. The legacy repositories, the mirror
  modules, and the legacy backend functions and their tests are deleted.
- **There is no data to preserve.** No released build exists and no user data is
  at stake, so the cutover drops the legacy tables outright and the on-device
  database is recreated from the new schema. No backfill, no dual-read window,
  no rollback plan.
- The schedule-variant widening and the status rename land *after* the cutover
  as their own steps, not folded into it.

### Notification delivery — two Tiers

See ADR 0003.

- **Medication Tier** keeps the existing behaviour unchanged: the strongest
  exact-alarm primitive the platform offers, selected into a bounded global
  Horizon across all active medication Reminders, diffed rather than rebuilt.
  This is the behaviour already verified on a physical device; it must not be
  disturbed.
- **Task Tier** uses ordinary notification triggers on a separate budget. Tasks
  do not enter the medication Horizon and therefore cannot displace a dose.
- Tier is derived from Kind. It is *not* derived from the existing priority
  field, which remains unused by any scheduling decision.
- The exact trigger API for the Task Tier is an open implementation choice and
  must be settled during the first Task-Tier ticket, then verified on a device.

### The two new pure functions

Both live in the shared package alongside the existing schedule engine, and both
are pure — no clock, no database, no globals.

- **Timeline projection.** Given the person's Reminders, their stored Reminder
  Instances, a window, and the current instant, it returns the rows the timeline
  renders — future Occurrences that have no Instance yet, and Instances for
  everything already acted on or missed — deduplicated on the pairing of
  Reminder and scheduled time. This is the same key the existing lazy-lookup
  path already uses, so the pattern is established rather than invented.
- **Sweep planner.** Given the same inputs plus a Grace Period and a lookback
  bound, it returns the Instances that should be minted as Missed. The caller
  persists them; the planner decides them.

The on-device repositories persist what these functions decide. They must not
contain the decisions themselves — that is what keeps them out of the test
story.

### Sweep execution

The sweep runs when the app comes to the foreground and before any Adherence
read. It is bounded by a lookback so that a long absence cannot materialise an
unbounded number of rows in one pass. The Grace Period and the lookback bound
are **not yet chosen** and must be settled before the sweep ticket is
implemented — see Further Notes.

### Surfaces

- The home surface becomes the timeline. It currently has no timeline at all.
- Natural-language entry becomes the primary creation surface for both Kinds,
  backed by the existing parser, which already performs Kind detection, priority
  words, weekday and clock-time extraction, and never rejects an input. It has
  no callers today.
- An Adherence surface and a Refill surface are added over the two existing
  tested pure functions, neither of which has any caller today.
- Medication-specific flows — drug search, dosage, supply counts — remain
  medication-only and are reached from a Reminder of that Kind.

### Privacy

See ADR 0004. Medications and tasks share one store, so every row receives
health-grade handling. Any path by which data leaves the device — analytics,
crash reporting, export, sharing — must be explicitly Kind-filtered and reviewed
before it ships. No third-party SDK may see row content.

## Testing Decisions

### What makes a good test here

A good test states an external behaviour and would survive a rewrite of the code
beneath it. It asserts on what a function returns or what a person would
observe — never on how the result was reached, which collaborator was called, or
in what order. A test that must be edited whenever the implementation is
refactored was testing the implementation.

For this work specifically: tests should express *rules*, not *shipped shapes*.
"A dose whose Grace Period has elapsed with no response becomes Missed" is a
rule. "The sweep returns an array of length three" is a shipped shape.

### The seam

**One primary seam: the shared package.** Nearly every decision above reduces to
a pure function, and the shared package is already the strongest seam in the
repository — pure functions, an existing runner, and a 100% coverage gate that
new code falls under automatically.

Tested there:

- the two new Schedule variants, through occurrence generation
- the timeline projection, including the union of Occurrences and Instances, the
  deduplication rule, and ordering
- the sweep planner, including the Grace Period boundary and the lookback bound
- Tier assignment from Kind, and the guarantee that a Task never enters the
  medication Horizon
- the Backlog completion rule — what Instance should exist, given a Reminder
  with no Schedule and the instant the person acted

Existing suites guard the rest: the backend test suite covers every exported
backend function against an in-memory deployment and will catch the status
rename and the validator widening; the type checker catches the rename
everywhere else.

**No new seams.** In particular, the on-device repositories are *not* given a
database test seam. They stay thin enough not to need one — they persist what
the pure functions decide. If a rule ends up inside a repository, that is a
signal the extraction was incomplete, not a signal to build a database harness.

**The one exception** is notification delivery, which cannot be tested without
an operating system. Task-Tier delivery is added as new cases to the existing
device harness, alongside the seven that already cover killed-app delivery, the
alarm primitive in use, idle-mode survival, permission-denied degradation,
reboot rescheduling, many-reminder stress, and weekly schedules.

### Prior art

- The existing schedule-engine and occurrence tests are the model for the new
  Schedule variants: pure, table-driven, with boundary conditions pinned
  explicitly.
- The existing conflict-resolution tests are the model for the sweep planner —
  a small truth table covering every combination, including the safety-critical
  case that a completed dose is never overwritten.
- The existing device harness is the model for Task-Tier verification, including
  emitting machine-readable evidence.

### A gate to set before starting

The device harness has not been run against current code. Since this work
changes the notification layer, the harness should be run to a known-good
baseline **before** the cutover begins, so that "did this break delivery?" is
answerable afterwards.

## Out of Scope

- **Caregivers.** A domain type and a field exist, but there is no table, no
  sharing model, and no push transport. Notifying a third party about someone's
  missed medication is a new aggregate, a permission model, and — under ADR
  0004 — an egress path needing deliberate design. Not a v1 bolt-on.
- **Push notifications.** Delivery stays local-only.
- **Projects and lists.** Grouping is by tags, which already exist on the model.
  No new aggregate.
- **Subtasks.** Requires a parent edge and a recursive completion rule.
- **Drug interaction checking.**
- **iOS and web.** The shared engine is platform-agnostic and ready; only the
  platform-specific notification layer would need a twin.
- **Encryption at rest.** Consciously deferred, and consciously *not* folded
  into the cutover, which was the cheapest moment to do it. Recorded in ADR
  0004 as an open gap rather than an oversight.
- **Collaboration and sharing of any kind.**
- **Travel across time zones.** Schedules use local calendar arithmetic and
  survive daylight-saving transitions; moving between zones is untested and
  stays that way for now.

## Further Notes

### Open questions that must be answered before the relevant ticket

- **The Grace Period.** How long after its moment does an Occurrence stay
  answerable before becoming Missed? A dose is not missed at 09:01. Left to
  implementation time, this gets chosen by whoever is working at whatever hour.
- **The lookback bound.** How far back does a single sweep reach?
- **Whether a *once* Schedule supports a date without a time.** An all-day task
  such as "renew insurance Tuesday" has no clock time, and occurrence generation
  currently assumes one.
- **The Task-Tier trigger mechanism.** Named as a decision above but not yet
  resolved to a specific API.

### The priority field

Priority is stored, validated, constrained in the on-device schema, and parsed
from words like "urgent" — and read by no scheduling decision anywhere. Tiering
is by Kind, so this work does not give it a job either. It should either earn
one or be removed; leaving a field that looks load-bearing and is not invites a
future contributor to assume it works.

### Most of this work is invisible

The cutover, the widening, the rename, and the sweep produce nothing a person
can see. They are the majority of the effort. The first visible payoff is the
timeline, which lands fifth. This is worth knowing before starting, and is not a
reason to reorder — the cutover is a hard prerequisite, not a preference.

### Stale documentation

The POC summary in the repository still describes a fifteen-commit state, an
earlier notification library, and a medication-only scope. It should be
reconciled or retired as part of this work rather than left to mislead.
