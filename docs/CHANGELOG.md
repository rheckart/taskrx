# Changelog

All notable changes to this project since commit `b534bfa` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for commit discipline (POC tags use `0.x.x`).

## [Unreleased]

### 2026-08-07 — A task reminder that cannot cost a dose its alarm

Closes #14. Tasks notify at their time, on their own Tier, and the guarantee
that they can never displace a dose is now structural at the operating-system
layer rather than at ours.

- **ADR 0007 — the Task Tier is WorkManager, not an inexact alarm.** The
  mechanism ADR 0003 left open and the spec listed as a question to settle
  first. `alarmManager: false` on a notify-kit timestamp trigger schedules a
  `OneTimeWorkRequest` instead of an alarm, so the two Tiers are not two
  parameters of one API but two subsystems: a task consumes a work request, a
  dose consumes an exact-alarm slot, and no quantity of the former can exhaust
  the latter. The rejected alternative was an inexact alarm — it delivers about
  as well and keeps both Kinds inside AlarmManager, where the difference between
  them is one enum value in one expression, which is a policy again.

  **The trap is recorded because it nearly is the default.** Omitting
  `alarmManager` reads as "no alarm manager" and means the opposite:
  notify-kit's validator defaults a timestamp trigger *to* AlarmManager with
  `SET_EXACT_AND_ALLOW_WHILE_IDLE`, so a task built without that line takes an
  exact, Doze-bypassing alarm on the exact-alarm budget — silently. The
  library's own TypeScript comment and the older upstream documentation
  disagree about which is the default, so both claims were checked against the
  shipped validator and the shipped Java rather than believed.

- `feat(notifications): a second budget, counted separately` —
  `selectTaskHorizon` in `@taskrx/shared` beside `selectMedicationHorizon`,
  both now one selector with a different Tier predicate. The two read disjoint
  sets of Reminders, so "a task never displaces a dose" is not an ordering rule
  that could be got wrong but an absence of any shared quantity to order. The
  task budget is 32 and is *not* a share of the medication 64; raising it cannot
  cost a dose its alarm, which is the property ADR 0003 asks for.

- `fix(notifications): the two Tiers are diffed in one pass` — **the defect this
  ticket would otherwise have shipped, and it arrives from the direction ADR
  0003 does not look.** `getTriggerNotificationIds()` returns every pending
  trigger regardless of subsystem, because notify-kit keeps one table, so a
  reschedule that knew about only medications computes every task trigger as
  stale and cancels it — and one that knew only tasks does the same to the
  doses. `_layout.tsx` reschedules on every transition to `active`, so that
  would have been one app switch wide, which is the exact shape of the snooze
  defect this module was already burned by. Task triggers carry a `task-` id
  namespace, the cancel pass reads the union of both desired sets, and the
  suite pins it in both directions.

- `feat(notifications): a deferred task comes back` — the snooze path chooses
  its Tier from the Kind exactly as the scheduler does. A task's deferral armed
  nothing at all until now, which is why #13's button said "Put off for now"
  rather than promising a reminder; it promises one now, and the Kind-specific
  `snooze` word is gone from the answer screen because the button finally means
  the same thing on both. This is the likeliest side door for a task to borrow
  the alarm-clock primitive — a person *asking* to be reminded again — and it
  is not hypothetical: it is how the app's last `SET_EXACT_AND_ALLOW_WHILE_IDLE`
  survived a migration that had moved every other path off it.

- `feat(notifications): a task channel of its own` — `task_reminders` at DEFAULT
  importance beside the HIGH-importance medication channel, so a task sounds and
  sits in the shade rather than bypassing Do Not Disturb (story 29). Two
  channels also let a person silence their tasks without touching their doses,
  which one channel could not offer. `createNotificationChannel` is
  `createNotificationChannels`.

- `test(harness): device cases 10 and 11` — a task notifies at its time and
  never registers as the device's next alarm clock; and a completely full task
  budget still leaves the dose armed and holding that registration. Both are
  local-only, so both join the `--all` baseline. Neither alone would mean
  anything: a build that armed no tasks would sail through 11, and one that
  armed every task with `SET_ALARM_CLOCK` — the failure ADR 0003 exists to
  prevent — would sail through 10. Case 11 asserts the flood filled the budget
  *before* it concludes anything about the dose, and reads the budget back from
  the app rather than spelling the number twice.

  Both are gated by `verify-harness-guards.sh`, with a timing check as well as
  the usual `pass:null` one. That is not ceremony: case 10 polls for delivery to
  a deadline minutes out, and `notification_has` answers 0 for an absent device
  exactly as it does for one that has not posted yet, so a case that entered
  that loop unattached would spin out its whole budget inside a gate that is
  meant to be device-free. Verified by mutation — it took 295s and the guard
  caught it.

- `fix(harness): make case 11 able to fail` — **the review found the case
  proving nothing, and both axes found it independently.** The flood was 40
  `daily` tasks at 00:05–00:20 against a dose ten minutes out, so every task was
  *later* than the dose, not sooner — and the inline comment claiming otherwise
  was simply false. A Kind-blind scheduler orders purely by time, so it would
  have ranked the dose first, armed it, and passed the case written to catch
  exactly that. The second error was quieter: 40 tasks clear the task budget of
  32 but not the **medication** budget of 64, and 64 is the number a Kind-blind
  scheduler would be ranking both Kinds inside — with fewer tasks than that
  there is room for the flood and the dose, so the displacement branch is
  unreachable however the times are ordered.

  It is now 80 `once` tasks, each a minute out and a second apart, all before
  the dose. `once` rather than `daily` because a daily schedule's next occurrence
  depends on the wall clock, and an unfalsifiable case that passes at 3pm is
  worse than no case. The shell asserts both premises — the flood exceeds the
  medication budget, and the task budget actually filled — *before* it reads any
  verdict about the dose, and reports a failure of either as the case having
  proved nothing rather than as a product pass. Both budgets are reported by the
  app so neither number is spelled twice. Two node cases now pin the fixture's
  premises, and each was mutation-checked against the original mistake it exists
  to catch.

- `refactor(notifications): one table for what the Tiers are told` — also from
  the review. Channel, importance, press action and alarm primitive were a
  `kind === "medication" ?` at each of them, five such branches inside one
  object literal in `snooze.ts` and a parallel pair of builders in
  `scheduler.ts`. That is the shape where one arm gets edited and the other does
  not, and here the consequence of drift is a task on the alarm-clock
  primitive — which is not hypothetical, it is how the snooze kept
  `SET_EXACT_AND_ALLOW_WHILE_IDLE` through the migration that removed it
  everywhere else. `TIER_DELIVERY` is now a record keyed by Tier, read by both
  arming paths, and legible as a table. `Tier` is exported from `@taskrx/shared`
  for the first time, because it is finally a value worth holding rather than a
  question worth asking.

Gates: `npm run verify` — 491 tests across three workspaces, 159 migration
checks, 16 harness guards. Every new assertion was mutation-checked separately:
13 mutations against the two Horizons and the Tier predicates, 15 against the
on-device Tier wiring and the cancel pass, and 2 against the corrected flood
fixture, each caught by the case naming it. One mutation was worth more than the
assertion it was aimed at: an over-broad `startsWith` in the harness's own count
meant a reminder id of `t-1-other` would be counted as a trigger of `t-1`, which
for case 11 is a budget reported as full when it was not — the count is now
exact about the remainder, and the case that found it says why.

Two assertions are **not** mutation-killed and are kept anyway, stated here
rather than left to be discovered: "a zero budget arms nothing" and "an empty
reminder list arms nothing" hold whether or not the early return exists, since
`slice(0, 0)` is empty too. They are parity with the medication Horizon's own
two, where a reader comparing the blocks would notice a gap.

**Verified on the device, 2026-08-07.** Pixel 8 Pro / Android 17 / API 37,
against commit `b695d4f` with a clean tree. **All nine local-only cases pass**,
which is both halves of what the ticket asks: the new Tier works, and the
medication baseline from #5 is unchanged.

- **Case 10 — the task Tier delivers, without the alarm-clock treatment.** The
  task notified at its time, and the device's next alarm clock stayed the
  unrelated 09:00 one rather than becoming the task's moment. That second half
  is the same reading case 2 uses to *prove* `setAlarmClock` for a dose, so the
  two cases are one instrument pointed at both Kinds.
- **Case 11 — the guarantee, measured.** 80 tasks, more than the 64-slot
  medication budget a Kind-blind scheduler would have been ranking them inside,
  filled the task Tier completely (32/32) — and the dose was still armed and
  still held the alarm-clock registration.
- **The mechanism itself was watched fire.** `WM-SystemJobScheduler` scheduled
  the work with `Minimum latency: +59s968ms`, `WM-WorkerWrapper` reported
  `Worker result SUCCESS` for `app.notifee.core.Worker` under the tag
  `trigger:task-…` at the armed second, and the notification posted. ADR 0007's
  claim about `alarmManager: false` is now an observation rather than a reading
  of the source.
- **Medication delivery re-verified:** killed-app delivery, the
  `SET_ALARM_CLOCK` primitive in `dumpsys`, deep Doze (`IDLE >
  IDLE_MAINTENANCE > …`, never returning to `ACTIVE` before the alarm fired),
  the exact-alarm-denied degradation, boot rescheduling (`before=3 after=3`,
  cross-checked against the OS per-uid tally), the 30-reminder stress cap and
  the weekly schedule. Every verdict matches the #5 baseline.

Evidence: `.omo/evidence/issue-14-task-tier-all-cases.json` (cases 1, 2, 4, 6,
7, 10, 11), `issue-14-case-3-doze.json`, `issue-14-case-5-boot.json` — all at
`b695d4f`, clean. `issue-14-task-tier-final.json` re-runs the two new cases at
`b0002d2` after the case-10 fix below, so the tip commit has its own artifact
rather than inheriting one. Cases 3
and 5 are separate invocations because each ends with the device asleep or
rebooted, and the keyguard that comes up then blocks every case after it — the
first `--all` attempt lost six cases that way, and reported them **unevaluated
rather than failed**, which is the harness's guards doing exactly their job.

- `fix(harness): case 10 tells a retracted trigger from an undelivered one` —
  **found by running it, and it had reported a product FAIL for a working
  mechanism.** With the app signed in, every deep link the case sends brings it
  to the foreground, `_layout.tsx` calls `rescheduleAll`, and its cancel pass
  removes the rig's task trigger because no SQLite row backs it — constraint 11,
  and the reason the local-only suite is documented as a signed-out one. The
  case now reads `pending-ids` before concluding: the id gone is a retraction and
  is recorded `unevaluated / setup_failed` naming the cause and the sign-in
  state; the id still pending with nothing posted is the genuine delivery
  failure it always meant. A rig condition must not be reportable as a product
  verdict, which is the whole premise of `verify-harness-guards.sh`.

**Deliberately not here.** The `medicationId` compatibility key is absent from a
task's payload, because it names a legacy table no task has ever had a row in
and `tap-handler.ts` already prefers `reminderId`. `scheduleForReminder` now
routes by Kind rather than refusing a task, since the debug screen it serves has
no business deciding a Tier either.

### 2026-08-06 — Tasks on the timeline: the fusion becomes real

Closes #13. A task can be added, it sits beside the doses in time order, and it
can be completed. This is the ticket the whole sequence existed for — the
cutover, the rename, the sync work, the projection and the sweep produced
nothing a person could see, and this is where the product idea first works end
to end.

- `feat(tasks): a second Kind, reachable` — `tasks/new.tsx` writes a
  `task`-kind Reminder with all five Schedule variants and none of the
  medication fields. A separate screen rather than `meds/new.tsx` with a Kind
  toggle, because the ticket asks that dosage, RxCUI, pill count and refill
  threshold be *absent* and not disabled, and a form hiding four fields and an
  entry point is two forms wearing one. What the two genuinely share — the
  schedule — is shared as a component.

- `feat(schedule): the weekly picker` — the last variant with no UI. It arrives
  now because "a weekday-only commitment does not nag me on Sunday" is a task
  story, and until a task could exist there was nothing that shape belonged to.
  Everything under it has supported `weekly` since the cutover; only the form
  could not say it, and `meds/index.tsx` has carried a rendering arm for a
  reminder nothing could author. Offered on the medication form too, which is
  the one thing here the ticket did not ask for: a weekly medication — a
  methotrexate dose, an injection on one day — is not a task-specific idea, and
  hiding a variant the picker beside it already renders would have been an
  arbitrary gap rather than a boundary.

- `feat(timeline): one list, both Kinds` — two lines. #10 wrote `projectTimeline`
  Kind-neutral and left the narrowing in the screen's two reads, so dropping the
  `"medication"` filters is the whole change to what the day contains. A dose and
  a task are told apart three ways, because one signal always fails somebody: a
  coloured left edge for a scan, the word beside the name for a read and for a
  colour-blind eye, and the same word spoken into the accessibility label, which
  is the only channel a screen reader has.

- `fix(adherence): tasks stop counting` — **the defect this ticket would
  otherwise have shipped.** `computeAdherence` excluded a *snoozed* task and
  counted every other one, so a week of ticked-off tasks would have pulled the
  medication figure toward 100% — story 37's "a productive week cannot disguise
  a bad medication week", inverted. Worse through the sweep, which mints `missed`
  for any Occurrence that ran out of time and does not care which Kind: a
  forgotten task would have read as a medication failure. It was unreachable
  while no task could exist and live from the moment one could.

- `fix(notifications): a task cannot displace a dose` — the other defect the
  fusion would have made live. `rescheduleAll` read every Kind and armed all of
  them with `SET_ALARM_CLOCK` into the bounded 64-slot Horizon, which is exactly
  what ADR 0003 forbids: a dozen routine tasks can fill that set and leave an
  evening insulin dose unarmed, silently and with no error. Tier is now a
  function of Kind in `@taskrx/shared` rather than an assumption, and
  `selectUpcomingOccurrences` is `selectMedicationHorizon` — the old name would
  have been a lie in the one place it matters most. `scheduleForReminder` and
  the snooze path are guarded too; the snooze was how the app's last
  `SET_EXACT_AND_ALLOW_WHILE_IDLE` once survived a migration off it.

- `fix(reminders): a snooze is written down` — snoozing armed a notification and
  left the Instance `pending`, so nothing about the deferral was recorded: the
  timeline still showed the moment unanswered, the sweep would later call it
  Missed, and Adherence — which treats a snoozed dose as a clinical fact and a
  snoozed task as a mere reschedule — never saw one of either. The status is now
  written before the alarm is armed, so a failure to arm still leaves the
  deferral recorded. `isAnswerable` in `@taskrx/shared` is what stops that
  becoming a trap: a snooze is "not now", not "no", so the row stays tappable and
  the on-device lookup finds it rather than opening a second beside it. The
  timeline and the repository ask the same function, because a row the list
  offers and the lookup refuses is a button that does nothing.

- `fix(timeline): close the code-review findings` — four, of which two were
  live defects rather than tidying. **The "next" row could not be told apart at
  a glance**: `entryNext` painted its left edge blue, overriding the Kind
  colour on precisely the one row the eye goes to first. It now says "next"
  with the tint and the badge, which are signals the Kind colour does not use.
  And **a reminder this device could not find was still armed** — the fallback
  title was "Medication", but a row that is absent is a row not known to be a
  medication, so a task reached from a stale deep link before its pull landed
  took the alarm-clock primitive. ADR 0003 says "by construction rather than by
  policy"; an unfound row now arms nothing either.

  The two smaller ones: the snooze instant was read from the clock twice, once
  for the stored `snoozedUntil` and once inside the arming, so the row and the
  alarm could name moments milliseconds apart — `snoozeReminder` now takes the
  instant the caller already wrote. And the schedule-type chooser had been
  copied byte-for-byte into both creation forms; it is `ScheduleTypeChooser`
  beside the picker now, which is where the thing the two forms genuinely share
  already lived.

  Vocabulary, from the same pass: "Dose recorded" and "Dose skipped" were
  CONTEXT.md's own _Avoid_ for the Dose entry — using it for a recorded taking —
  and the fixtures had reached for "chore", which is _Avoid_ for Task.

Gates: `npm run verify` — 461 tests across three workspaces, 159 migration
checks, harness guards. Every new assertion was mutation-checked separately: 6
against Tier, 4 against the adherence rule, 7 against the fused projection, 5
against `isAnswerable`, and 4 against the on-device Tier and clock guards, each
caught by the case naming it. One did not, at first: the case pinning that the
snooze arms the caller's instant used a fixture of `NOW + 15 minutes`, which is
exactly what a re-read of the frozen test clock produces, so it passed against
the mutation it existed to catch. The fixture is an offset no recomputation
arrives at now, and it says so.

**Deliberately not here, and stated so on the screen.** A snoozed task records
its deferral and arms nothing, because Tier is Kind and the task Tier has no
trigger until #14 — so the button says "Put off for now" rather than promising a
reminder that is not coming, and the timeline row reads "Snoozed until 14:35"
rather than an unqualified "Snoozed". That label is the whole visible content of
a task's deferral until #14 gives it a trigger. The row does **not** move to the
new moment: `scheduledTime` is the instant an Instance occupies (ADR 0002) and
half the pairing key every stored answer is found by, so moving it would unmake
the deduplication. The medication path is untouched, which is what keeps Phase
1's device evidence valid.

**Not closed, and now reachable in one more way:** #21, the duplicate `pending`
Instance minted when an already-answered moment is reopened. The snooze case is
closed by `isAnswerable`; completed and skipped are not, and belong there. Two
more, both flagged by the spec review and both left: an overdue **task** stops
being actionable once the sweep calls it Missed, which is story 34 and the same
`missed` exclusion #21 owns; and `dose/history.tsx` stays medication-only, so a
completed task appears on the timeline and not in History — the honest home for
that is the Adherence surface in #16.

### 2026-08-06 — Missed doses stop vanishing, and a Reminder can happen once or never

Closes #11 and #12. Two decisions were taken before either was written, as both
tickets demand, and both are ADRs rather than comments so the next reader finds
them where the other four live.

- **ADR 0005 — a Grace Period of two hours and a lookback of seven days.** Two
  hours is the width of "I took it with lunch instead of at breakfast"; below an
  hour the sweep converts ordinary lateness into a permanent `missed` row, and
  above about six the morning is still unsettled when the evening comes due.
  Seven days is long enough to cover a phone left flat over a long weekend and
  short enough that returning after months does not materialise thousands of
  rows on one foreground. The consequence is stated rather than hidden: a moment
  more than seven days old that was never answered stays absent permanently, so
  a long gap reads as no data rather than as a wall of Missed.

- **ADR 0006 — `once` carries an instant; all-day is not a variant yet.** Every
  consumer downstream is keyed on an instant — occurrence generation, the
  notification Horizon, the timeline's sort and its pairing key, and
  `scheduledTime` with its four time-ordered indexes — so a nullable time would
  have five consumers inventing five different midnights. An all-day Reminder
  differs in behaviour, not just in precision, so it earns its own variant when
  the timeline is ready to place one.

- `feat(sweep): record what nobody answered` — `planMissedSweep` in
  `@taskrx/shared`, pure and under the 100% gate. A moment out of time is in one
  of two states and the caller does different things with them: no Instance
  exists and one is minted already `missed`, or a `pending` Instance exists and
  moves to `missed`. An answer is never overruled, `snoozed` included. The
  device half (`src/sweep/runner.ts`) reads, hands over, and writes; its calls
  are chained, because two overlapping sweeps would both read "no Instance at
  09:00" and the fix for a moment that vanished would be a moment recorded
  twice. It runs on foreground and again before the timeline reads, so a moment
  the sweep has judged is not rendered as still answerable.

  This is what makes Adherence honest: it counts Instances and excludes
  `pending` ones, and until now the only moments that ever became rows were the
  ones somebody acted on — which is why taking 1 dose in 30 scored 100%.

- `feat(schedule): let a reminder happen once, or never` — the domain union and
  the Convex validator widened together, as the spec requires of two closed
  unions. `once` yields exactly one Occurrence and is spent; `none` yields none
  at all, which is the Backlog. `none` replaces a misconfiguration that used to
  impersonate it — an empty `daily` schedule also produces nothing — and
  `isBacklog` is the one rule that tells the two apart.

- `feat(backlog): a surface for commitments with no moment` — rendered from
  `reminders` rows, because ADR 0002 makes a Backlog Reminder invisible to any
  query over Instances until it is acted on. Completing one goes through
  `planBacklogCompletion`, which mints the Instance at the moment of the act
  with `scheduledTime` equal to `completedAt`; giving one a time is the ordinary
  reminder update, and moves it onto the timeline.

- `refactor(picker): speak the domain union` — `SchedulePicker` took a POC pair
  with no discriminator, told apart by a separate `scheduleType` prop, and
  `meds/new.tsx` converted at save. `@taskrx/shared` carried `ScheduleType`,
  `DailySchedule` and `IntervalSchedule` for that component alone and recorded
  they were "due to be replaced wholesale when the picker grows the `once` and
  `none` variants". They are gone, and with them the last mapping between two
  spellings of a schedule.

- `fix(sweep): close the code-review findings` — the spec axis caught that the
  planner **invented misses predating the Reminder itself**. Day-based Occurrence
  generation has no start of its own, so a medication added this morning was
  swept across the whole lookback and recorded as a week of doses nobody could
  have taken — Adherence falling for a treatment that had not begun, which is
  the exact inverse of what #11 asks for. Every fixture used `createdAt: 0`, so
  no case could see it. The sweep now starts at the later of the lookback and
  the Reminder's creation, and ADR 0005 records that second floor.

  Three more from the same pass. The timeline **awaited** the sweep before
  rendering, so the long-absence case the lookback exists for would have sat on
  a skeleton through hundreds of writes; the day now renders first and
  re-projects only if the sweep recorded something. A malformed `schedule`
  column degraded to an empty `daily` schedule — reintroducing, at the read
  path, the misconfiguration this ticket removed — and now degrades to `none`,
  which also puts a corrupt row where it can be repaired, since the Backlog's
  "give it a time" is the only edit path an existing reminder has. And schedule
  validity had been written inside `SchedulePicker`, a domain rule outside the
  one seam the spec names; it is `isWellFormedSchedule` in `@taskrx/shared` now,
  asked by both surfaces that set a schedule.

  The standards axis flagged a per-reminder ceiling in the planner as
  contradicting ADR 0005's rejection of row bounds. It is a runaway guard rather
  than the sweep's bound — the Occurrence engine counts rather than ranges, so
  some count must be passed — and the ADR now says so rather than leaving the
  two to look alike.

Gates: `npm run verify` — 432 tests across three workspaces, 159 migration
checks, harness guards. Every new assertion was mutation-checked separately: 10
against the sweep planner, 4 against the backlog rule, 5 against the sweep
runner, 4 against the schedule union and 2 against the Convex validator, each
caught by the case naming it. The 1-of-30 case is now stated as a test rather
than as a claim: the planner's verdicts are fed to `computeAdherence`, which
reports 100% before the sweep and under 10% after.

**Not decided here, and worth deciding:** completing a Backlog Reminder leaves
it in the Backlog. The Instance records the act, but nothing in the domain says
whether an unscheduled commitment is one-shot ("call the pharmacy") or standing
("painkiller as needed"), and inventing a retirement rule for one Kind would
have been guesswork on the other. Someone finished with one stops the Reminder
itself. This belongs with the Task work in #13, where one-off tasks become the
common case.

**Also still open, and unchanged by this:** answering an occurrence still mints
a duplicate `pending` Instance beside the answered one. The sweep now settles
both rather than leaving the survivor `pending` forever, which is the right
behaviour for rows that exist — but they should not both exist. It has no issue
of its own.

### 2026-08-06 — The Today timeline

Closes #10. The home surface was a stub; it is now the day as one ordered
list. The first thing in this sequence a person can actually see — the
cutover, the status rename and the sync work before it produced nothing
visible.

- `feat(timeline): show the day as one ordered list` — `projectTimeline` in
  `@taskrx/shared`, pure and under the 100% gate. A day is a union of two
  different kinds of thing: Occurrences, computed from a Schedule and never
  stored, and Reminder Instances, the stored record of what became of one. Both
  can describe the same moment, so they are deduplicated on the pairing of
  Reminder and scheduled time — the key the lazy-lookup path already used — and
  the stored answer wins, because it is a fact where the Occurrence is only a
  prediction. The screen picks the window, reads two tables, and renders the
  result; it decides nothing.

  Deliberately undecided here: an unanswered Occurrence whose moment has passed
  stays `pending` rather than becoming Missed, because that needs the Grace
  Period the spec leaves open for the sweep in #11. Nothing in the projection
  picks one by implication. It is also Kind-neutral, so the fusion in #13 is a
  new caller rather than a rewrite.

- `fix(timeline): close the code-review findings` — the review caught that
  replacing the stub had taken the app's **only sign-out path** with it
  (`grep signOut apps/mobile` returned nothing), and that the handled-state
  criterion held in the projection but not in the app: the list read on mount,
  and answering navigates away and back. Reloads on focus now. Vocabulary
  corrected to CONTEXT.md throughout — "dose" is the quantity of a drug, not a
  scheduled or recorded taking, which are Occurrence and Reminder Instance.

- `fix(timeline): collapse two stored answers claiming one moment` — **found on
  the device, not by any test or either review axis.** Aug 2 rendered the 17:36
  moment twice, once `pending` and once `completed`, with React's "two children
  with the same key". The projection deduplicated an Occurrence against an
  Instance but never an Instance against another Instance. Two should not
  exist; two do, because of the duplicate-instance defect below. An answer now
  beats `pending`, later `updatedAt` wins between two answers, and an exact tie
  falls to the instance id.

Gates: `npm run verify` — 372 tests across three workspaces, 159 migration
checks, harness guards. 21 mutations against the projection, each caught by the
case naming it. Verified on the Pixel 8 Pro against the issue #7 round-trip
data.

**Still open, and now visible as live rows rather than a note:** answering
mints a duplicate `pending` Reminder Instance beside the `completed` one, and
both reach Convex. The timeline no longer *renders* the duplicate, but both
rows are still written and adherence still counts from them. It has no issue of
its own. The other two findings from 2026-08-03 — the missing
`POST_NOTIFICATIONS` request and the post-notification SQLite lock — are also
untracked and unfixed.

### 2026-08-05 — A taken dose stays taken, on the server too

Closes the criterion issue #7 left unticked, and the server-side hole its
closing comment recorded but did not fix. Story 44 of the fused-timeline spec —
"a dose recorded on one device is never overwritten by stale data from another"
— was true of the device and false of the deployment.

- `fix(convex): make a completed reminder instance terminal` — `markSkipped`,
  `markMissed` and `snooze` patched `status` unconditionally after their
  ownership check. A second device pushing an answer it decided before it
  learned of the completion flipped the server copy, and a third install then
  pulled `missed` for a dose that was taken — its own local guard cannot help,
  because a fresh install has no local row to protect. All three now refuse, and
  so does `create`'s upsert path, which is defence in depth rather than a live
  hole: a replayed create carries the newest status its device holds and the
  queue drains in order.

  The refusal is a **no-op, not a throw**. The caller is the sync worker
  draining its queue, where a stale entry is routine; throwing would retry it to
  the dead-letter threshold and raise a data-loss warning for a write the server
  was right to ignore. `does not report a refusal as an error` pins that choice.

- `refactor(sync): decide the pull conflict rules where a test can run them` —
  both `shouldApply…` guards lived inside the two `upsertMany` implementations,
  behind `getDb()` and so behind expo-sqlite, which no node test loads. The rule
  was executed as a pure function by `conflict.test.ts` and the surrounding pass
  by `pull.test.ts`, but nothing ran the two together — proven of the decision
  and of the pass, never of the pair, which is exactly what #7 left open. The
  decisions moved up into `applyRemotePull`; the repositories now write every
  row they are handed. This is what `docs/specs/fused-timeline.md` asks for —
  "the on-device repositories persist what these functions decide" — and it
  needed no new database test seam, which that spec forbids.

One existing Convex case changed rather than being added to: `defaults an
omitted status to pending` moved its fixture off `completed`, because it had
been asserting that a completed instance demotes to pending — the corruption
now forbidden. Its subject was the defaulting, not the starting status.

Gates: `npm run verify` — 340 tests across three workspaces, 159 migration
checks, harness guards. Every new assertion was mutation-checked separately, 11
mutations, each caught by the case naming it.

**Not closed here — the legacy tables in the deployment (#8).** `medications`
and `doseLogs` still exist in the local dev deployment as empty, undeclared
metadata; Convex keeps a table when you remove it from `schema.ts` rather than
destroying data on a file edit. Confirmed empty this time rather than inferred:
`inferredSchema: Never` on both. The production deployment `cool-impala-411`
was recorded as uninspected in #8 and is now inspected — it holds **no tables at
all**, so nothing was ever deployed there and nothing is at stake. Removing the
two remaining tables is a dashboard action.

### 2026-08-03 — Criteria 6 and 9 verified on the device

The last two `⏸️ Pending` POC criteria are now verified and, unlike the seven
the issue #7 round trip answered by hand, re-runnable.

- `feat(mobile): expose the signed-in harness actions` — six harness actions
  that read and write the real SQLite tables as the authenticated user, so the
  adb harness can drive the signed-in tree and not just the local-only one.
  Every one refuses without a Clerk subject rather than guessing at it.
- `feat(harness): add device cases 8 and 9 for criteria 6 and 9` — a real tap
  on a real notification, and an airplane-mode round trip measured from the
  Convex deployment rather than from the app's own `sync_status`. Not part of
  `--all`, which stays the local-only delivery baseline.
- `feat(notifications): log every notification-tap outcome` — a tap that went
  nowhere used to be completely silent.
- `fix(harness): verify the app process actually restarted` — `am kill`
  declines to kill a process Android still considers foreground, silently.
- `feat(harness): purge the rows cases 8 and 9 seed` — `--purge` hard-deletes
  them from Convex and then the device, in that order. Each run leaves a
  fabricated reminder and an answered dose, and adherence is computed from
  exactly those instance rows; the in-case `cleanup-seeded` only soft-deletes.
  `harnessData.purgeSeeded` is the only hard delete in the deployment, which is
  why it is an `internalMutation` and why its blast radius is pinned by tests
  on both sides.

Three findings are recorded in `docs/poc-results.md` and **not** fixed here,
the first of which stops reminders reaching a real user:

- A fresh install never requests `POST_NOTIFICATIONS`; the only caller is a
  debug screen. Alarms fire and Android discards every notification.
- Answering a dose mints a duplicate `pending` instance beside the `completed`
  one, and both reach Convex. Adherence counts from these rows.
- After a notification fires, the app's SQLite writes fail with "database is
  locked", surviving a process restart.

### 2026-07-26 — Stack revisit (return to Convex + Expo) + Phase 1

The stack briefly moved to .NET MAUI + ASP.NET Core + PostgreSQL + Aspire and
back. The .NET line of work is preserved on the `archive/dotnet-maui` branch
(tag `archive/dotnet-maui-v0`); `main` continues the original Convex + Expo
target with the fixes below. Rationale in `.omo/plans/convex-expo-revisit.md`.

#### Phase 0 — Rescue + toolchain green

- `chore(archive): .NET MAUI + ASP.NET Core implementation (rescued WIP)` —
  Committed the entire uncommitted .NET tree (287 files across `src/TaskRx.Core`,
  `src/TaskRx.Data`, `apps/TaskRx.Server`, `apps/TaskRx.Maui`,
  `apps/TaskRx.AppHost`, `TaskRx.slnx`) to a new `archive/dotnet-maui` branch
  and tagged `archive/dotnet-maui-v0` before any restoration work. Includes the
  orphaned `src/TaskRx.Maui` project whose `AlarmManager.SetAlarmClock` pipeline
  the user had verified firing on a physical Android 17 device — that
  empirical result directly informed the Phase 1 AlarmType choice below.
- `chore(repo): restore shared tooling on main after .NET archive` —
  Restored `main` to the Expo POC tree (Expo SDK 56 / RN 0.85.3) and carried
  forward the non-.NET tooling assets (`AGENTS.md`, `docs/agents/`, `.claude/`,
  `.pi/`, `opencode.jsonc`, `skills-lock.json`, research doc).
- `fix(lint): resolve 8 pre-existing errors from post-POC commits` — Fixed the
  drift introduced by `b5c70d1` / `abab84a` / `cfec46e` after the POC summary
  was written (`docs/POC-SUMMARY.md` claimed lint was green; it was, at tag
  `poc-v0.1.0`, but has been broken since). `npm run typecheck`, `npm run lint`,
  and `npm run -w packages/shared test` (23/23) now all exit 0 on `main`.

#### Phase 1 Wave 1 — Deps + hygiene

- `fix(deps): remove duplicate @clerk/expo (unused) from mobile app` — Two
  major generations of the Clerk SDK were in `apps/mobile/package.json`
  (`@clerk/clerk-expo@^2.19.31` + `@clerk/expo@^3.5.3`). Grep confirmed 13
  imports of the former and 0 of the latter; removed the dead package.
- `feat(notifications): migrate from archived Notifee to react-native-notify-kit` —
  Invertase archived `@notifee/react-native` on 2026-04-07; the archived
  README now names `react-native-notify-kit` the drop-in maintained fork.
  Dev target RN 0.85.3 (== ours), Apache-2.0, TurboModules only. Fixes the
  killed-app trigger failure (upstream #1100), silent exact-alarm-denial
  drop, DST shift (#875), and pre-request-state (#1237) bugs. Public API
  100% compatible — swap the import path only.
- `fix(sync): dead-letter queue entries after MAX_ATTEMPTS` — SQLite
  migration `003_sync_queue_dead_letter.sql` adds `dead_letter INTEGER`
  column + index. `pending()` now filters dead-lettered rows so the worker
  no longer retries them forever; `deadLettered()` + `requeue(id)` expose
  the terminal set for inspection and manual recovery. Rows are NEVER
  deleted — a dropped medication dose log is unacceptable data loss.
- `refactor(sync): single tested source of truth for conflict resolution` —
  Consolidated the orphaned `apps/mobile/src/sync/conflict.ts` helpers
  (zero call sites; used `>` semantics) with the inline repo `upsertMany`
  logic (`>=` semantics) into `@taskrx/shared`. Picked the repo semantics
  as authoritative and covered them with 10 vitest cases including the
  safety-critical "locally-taken dose is never overwritten" rule.

#### Phase 1 Wave 2 — Notification core

- `fix(notifications): implement real exact-alarm permission lifecycle` —
  Replaced the hardcoded `Promise.resolve(true)` stub in
  `checkExactAlarmPermission()` (which meant the app literally could not
  detect a denial) with the real notify-kit `getNotificationSettings()`
  read. Extracted the derivation to `@taskrx/shared` with 11 vitest cases
  covering iOS null / Android < 12 NOT_SUPPORTED / Android 12+ ENABLED |
  DISABLED and the notify-kit #1237 fix (NOT_DETERMINED before request).
- `fix(notifications): use SET_ALARM_CLOCK with a global 64-dose diff-based horizon` —
  Highest-value single change in Phase 1.
  1. **AlarmType**: switched from `SET_EXACT_AND_ALLOW_WHILE_IDLE`
     (weaker; no OEM resilience) to `SET_ALARM_CLOCK` — the mechanism the
     user has empirically verified firing on a physical Android 17
     device (native MAUI on `archive/dotnet-maui`), and the only
     AlarmType that survives vendor aggressive-kill (Xiaomi MIUI, Oppo
     ColorOS, Huawei EMUI, Vivo FuntouchOS). Same primitive the stock
     Clock app uses. Deliberate tradeoff: persistent status-bar
     alarm-clock glyph while any trigger is pending — acceptable / arguably
     a feature for a medication app. Degrades to `setAndAllowWhileIdle`
     when exact-alarm permission is absent rather than dropping the
     trigger.
  2. **Horizon**: replaced per-medication 10-dose scheduling (which
     scaled to ~300 alarms at 30 meds and blew iOS's hard cap) with a
     **global** 64-dose priority-queue selector in `@taskrx/shared`
     (10 vitest cases).
  3. **Diff-based reschedule**: replaced `cancelAll()`-then-recreate in
     the foreground path with a diff — compute desired 64, read current
     via `notifee.getTriggerNotificationIds()`, cancel only stale,
     create only missing. Deterministic IDs make this idempotent; a
     second reschedule with unchanged data does nothing.

#### Phase 1 Wave 3 — Test harness

- `test(notifications): adb-driven exact-alarm/Doze/killed-app/boot/stress harness` —
  Codified the six manual test scenarios in `docs/poc-notification-tests.md`
  and `docs/poc-stress-tests.md` (which never ran and left 12 of 13 POC
  criteria pending) into an executable bash script at
  `apps/mobile/scripts/notification-harness.sh` and a hidden
  `apps/mobile/app/harness.tsx` deep-link route. Every case is
  agent-executable via adb (appops, deviceidle, force-stop, reboot,
  dumpsys, logcat) and emits JSON to `.omo/evidence/`. Auth gate has a
  documented security carve-out so `/harness` runs on a fresh install.

#### Phase 1 Wave 4 — Emulator regression gate

- `fix(android): bump compileSdkVersion 35 -> 36 for androidx.core 1.18.0` —
  Gradle failed on the transitive `androidx.core:1.18.0` requirement of
  compileSdk >= 36. Bumped compileSdk; `targetSdkVersion` stays at 34
  (runtime behaviour unchanged). APK builds cleanly on Node 26 + Expo
  SDK 56 + notify-kit New Arch (`BUILD SUCCESSFUL in 4m 25s`, 260MB
  debug APK).

### Phase 1 verification status

- All code fixes committed and passing static verification: `typecheck` 0,
  `lint` 0, `packages/shared` **54/54** vitest cases pass (23 schedule +
  10 conflict + 11 permission-state + 10 horizon).
- Debug APK builds and installs on the API 36 emulator. Notifee/notify-kit
  native side initializes at process start. Metro bundles cleanly (1986
  modules in 8s). Full evidence in
  `.omo/evidence/task-{3,5,12}-convex-expo-revisit.log`.
- The physical Android 17 device run is the authoritative test and is
  pending user action (plug in the device with USB debugging on, tap the
  discovered "TaskRx" server card in DevLauncher once, then
  `apps/mobile/scripts/notification-harness.sh --all`).

## [0.1.0] - POC

### Day 1 — Monorepo + Tooling

- `chore: scaffold monorepo (Day 1 of POC plan)` — Created npm workspaces for `apps/mobile`, `packages/convex`, and `packages/shared`; configured TypeScript strict, ESLint, Prettier, Husky, and lint-staged.

### Day 2 — Auth + Backend Skeleton

- `feat(mobile): wire Clerk auth with expo-router groups` — Added `@clerk/clerk-expo`, split routes into `(auth)` and `(app)` groups, and wired `useAuth()` for navigation gating.
- `feat(convex): add schema, Clerk JWT, stubbed functions` — Created Convex schema per plan §6.2, added Clerk JWT verifier, and stubbed `medications` / `doseLogs` functions.

### Day 3 — Medication Add Flow

- `feat(convex): implement RxNorm search Action (Day 3 POC)` — Implemented `rxnorm.search` Convex Action calling the NLM RxNorm approximate-term API.
- `feat(mobile): add medication UI flow with SQLite repo (Day 3 POC)` — Built medication search, add form with schedule picker, and local SQLite repo with sync-queue enqueueing.

### Day 4 — Notification Permissions + Schedule Engine

- `feat(mobile): wire Notifee + Android notification scheduler (Day 4 POC)` — Added Android notification permissions, reminder channel, and Notifee-based rolling scheduler.
- `fix(shared): reach 100% schedule engine coverage with v8 ignore` — Hardened schedule-engine unit tests to 100% coverage.

### Day 5 — Dose Logging + Local-Only Verification

- `feat(mobile): dose logging flow (repo + UI + history) — Day 5 POC` — Added dose-log repo, dose-log screen (take / skip / snooze), and 7-day history view.

### Day 6 — Android Notification End-to-End

- `chore(mobile): Day 6 — EAS config, debug APK build, notification test script` — Added `eas.json`, debug APK build script, and notification test documentation.

### Day 7 — Convex Sync (Background + Foreground)

- `feat(convex): implement medications/doseLogs mutations with localId mapping` — Added Convex mutations for medications and dose logs with local-ID mapping.
- `feat(mobile): sync worker, pull hooks, conflict resolution` — Implemented sync worker, realtime pull hooks, and last-write-wins conflict resolution.
- `feat(ui): sync status badge and local-first reads` — Added sync-status badge and switched reads to local-first SQLite.

### Day 8 — Android Exact-Alarm Stress + Offline-First Stress

- `chore(poc): Day 8 — stress test scripts (30-med exact-alarm + offline-first)` — Added stress-seed scripts and documentation for 30-med exact-alarm and offline-first scenarios.

### Day 9 — UX Polish + EAS Production Build Prep

- `feat(ui): polish — toasts, loading states, icon` — Added custom `Toast` + `useToast`, skeleton loading states, and generated app icon + splash screen.
- `chore(build): refine eas.json + production build script` — Added `production` profile, Play Store submit stub, runtime version policy, and production build/deploy scripts.
- `docs(poc): README + CHANGELOG + architecture + decision templates` — Rewrote README, added CHANGELOG, architecture doc, and post-POC decision/results templates.
