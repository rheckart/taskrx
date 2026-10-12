# Changelog

All notable changes to this project since commit `b534bfa` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for commit discipline (POC tags use `0.x.x`).

## [Unreleased]

### 2026-10-12 — A taken dose spends its pills

Settles the "known gap" left open on 2026-08-14. The rule is `supplyAfterDose` in `:core`, and
the Android app applies it inside the same transaction as the answer.

- Only a change into Completed spends: `pillsPerDose`, or one when none is set (the server's
  `consumePills` default). The count stops at zero.
- Skipped, Missed and Snoozed cost nothing. A dose backfilled as already taken (the Backlog)
  spends once. Answering the same dose twice, or taking it after a snooze, spends once.
- A task, or a medication with no count, spends nothing.
- A Completed that arrives from the server spends nothing here; the other device spent the pills
  and the new count comes with the Reminder document.
- Nothing moves an Instance out of Completed, so there is no refund path.
- The new count is written as an ordinary Reminder update, so it queues and syncs like any
  edit. The server's `consumePills` is still not called: calling it as well would count the
  dose twice.

### 2026-08-14 — Know before you run out

Closes #17. The refill planner was the third piece of tested, never-called code
in the repository, and unlike the adherence calculator it could not simply be
given a caller: its rule was the wrong rule. `RefillPlanner.cs:27` raised a
refill when `pillsRemaining <= dosesInLookaheadWindow * 3` — a comparison of
pills against doses, so `pillsPerDose` was checked for being positive and then
never used, and a medication taken two at a time was projected to last exactly as
long as one taken singly. It also had no notion of a date, so "how many days do I
have left" was not a question it could answer at all. Both are acceptance
criteria on the ticket.

So the threshold is replaced rather than wrapped. `projectRefill` joins the
shared package and the 100% gate: the supply is converted to doses, the doses are
walked against the Reminder's real Schedule to find the moment the supply first
cannot cover one, and the refill is raised when that moment falls inside the
warning window the Reminder itself carries. `planRefill` keeps its job of
building what gets raised and now reads its verdict from there, so the days
showing on screen and the Reminder standing beside them cannot come from
different arithmetic.

- **Days remaining is counted between local midnights**, via a new
  `localCalendarDaysBetween` beside `localDayBounds`. Dividing by 86_400_000
  puts 23 hours between two midnights each spring and 25 each autumn, which
  truncates or rounds up to a warning arriving on the wrong day. Pinned across
  both US transitions.

- **A medication with no recorded supply gets a reason, never a zero.** The
  projection returns `no-supply`, `no-dose-size`, `stopped` or `not-a-medication`
  rather than a count, because "nobody has said how many I have" and "the bottle
  is empty" are opposite facts that a `0` cannot tell apart. The surface renders
  each as a sentence and offers the box to put it right; the unprojectable rows
  sort last, since a missing number is not urgent.

- **A supply that outlasts the schedule horizon reports no date** rather than a
  nearer wrong one. `interval` schedules have no horizon of their own, so the
  walk stops itself at 512 doses; that cap can only ever answer "further off
  than I can see".

- **The raise is a Backlog task, and it is reversible.** `planRefill` now builds
  it with `noneTimes()` where the C# used a daily schedule with no times — the
  variant `none` was added to replace, which produces no Occurrences while
  claiming a daily schedule, so the raised Reminder would have reached neither
  the timeline nor the Backlog. `reconcileRefills` decides what the Backlog
  should gain and lose: it raises when the projection asks, raises nothing when a
  warning is already standing (including twice in one pass, for two medications
  entered under one name), and withdraws one once the supply has been topped up
  or the medication stopped. Withdrawing otherwise needs the supply to still be
  visible — clearing the pill count leaves the projection blind rather than
  reassured, and a warning retired on that basis is the app forgetting rather
  than the person collecting.

- **Which Refill Reminders to raise is a rule, so it is gated.** It lived in
  `apps/mobile/src/refill/read.ts` for a first draft, where nothing covered it
  and the only way to exercise it was a hand-rolled fake of expo-sqlite. The spec
  is explicit — "if a rule ends up inside a repository, that is a signal the
  extraction was incomplete" — so it moved to `refill/reconcile.ts`, and the
  device read is now two queries, the writes, and a sort.

- The surface's wording moved to `src/format/refill.ts` for the same reason
  `formatPercent` is not inside `adherence.tsx`: on this screen the wrong
  sentence over the right number is the whole failure mode. It carries
  `CONTEXT.md`'s vocabulary, so a supply is "enough for 12 more" and never "12
  more doses" — the glossary reserves Dose for the quantity taken at one time and
  the adherence headline settled the same point when it became "14 of 18 taken".

- The refill surface titles itself "Supplies", says outright that a task has
  nothing to run out of, and discloses that a blank warning window means seven
  days rather than leaving the box a lie about what is in force. `NumberField`
  moved out of `add.tsx` into `src/components/`, since both surfaces now edit the
  same three numbers and an empty box has to keep meaning the same thing in both.

`RefillPlannerTests.cs`'s translated cases all pinned the old threshold. They are
replaced by cases stating the rule that supersedes it — that thirty pills taken
two at a time last fifteen days and not thirty, and that sixteen pills sit inside
a seven-day window or outside it depending on how many are swallowed at once.

**Known gap, recorded rather than hidden: nothing consumes the supply.** No path
decrements `pillsRemaining` when a dose is recorded — the backend's
`consumePills` has no callers — so a projection only moves when someone edits the
count by hand. Thirty pills at one a day reads "30 days left" tomorrow as well as
today, and the warning fires only if the number entered was already inside the
window. Everything #17 asked for is honest about the numbers it was given, but
"know before you run out" is only fully true once a taken dose spends a pill.
That is its own change: it has to settle what a skipped or missed Occurrence
costs, and it touches every path that answers one. It is also why this read has a
single caller — until the supply moves on its own, the only thing that changes a
projection is the edit made on the surface the read feeds.

### 2026-08-13 — The breakdown covers the period, not just the days with data

Follows #16. `computeAdherence`'s `daily` listed only the days that had counted
instances, so a day nobody recorded anything on was absent from the array
entirely — and on screen the list simply stepped from the 10th to the 12th, which
is indistinguishable from a day the reader scrolled past. ADR 0005 endorses the
underlying reading, that a gap in history "reads as no data rather than as a wall
of Missed"; it does not endorse a gap nobody can see, which is an omission rather
than a reading.

`daily` now carries one row per local calendar day of the window. The widening is
in the pure function rather than in the screen, because "which days does a period
contain" is a rule and `docs/specs/fused-timeline.md` puts rules in the shared
package — filling the gaps in `adherence.tsx` would have been the extraction
being incomplete.

- An empty day carries `total: 0`, and **`total` is what identifies it**. Its
  `percent` is `0` only because `0/0` has to be some number; rendered as a
  percentage it would report a day with nothing due as a day on which every dose
  was missed, which is the opposite fact and, on this surface, an accusation.
  `DayAdherence` now says to read `total` and never `percent`, and
  `adherence.tsx` renders those rows as "No doses".

- Deliberately **not** "every day a dose was due". The calculator sees Instances
  and never Reminders, so it cannot tell a day with nothing scheduled from a day
  whose history the sweep could not reach. Both are honestly "no doses recorded",
  and neither is invented as a miss.

- The day walk steps with `setDate(getDate() + 1)`, not by adding 86_400_000. The
  fixed step walks off midnight at a transition: across the US fall-back it emits
  `2027-11-07` twice and loses a day off the end. Pinned by the fall-back case.

- Enumerating the window in order removed the sort entirely — the days arrive
  ascending rather than being discovered from the data and reordered afterwards.

Nine existing cases encoded the narrow contract and were rewritten to state the
same rules against the wider one. Two were improved by it rather than merely
adapted: "a day of nothing but tasks" now asserts that day has *zero medication
commitments* instead of asserting it is absent, which is the rule it always meant;
and the two daylight-saving cases now assert three rows across a transition, which
pins the day walk as well as the grouping.

### 2026-08-13 — An honest picture of how treatment is going

Closes #16. The adherence calculator was ported from `AdherenceCalculator.cs`
months ago, fully tested, under the 100% gate — and referenced only in a comment
on `dose/history.tsx`. Nothing ever called it. That was correct until now: it was
blocked on #11 for a reason, because before the missed sweep an unanswered dose
left no row at all, so the calculator counted only the doses a person had
answered and someone taking 1 of 30 read as 100%. Shipping the surface earlier
would not have been imprecise, it would have presented a falsehood as a medical
fact. #11 closed, so the number is now worth showing.

- `feat(shared): the window the figure covers` — `adherence/period.ts`.
  **Seven local calendar days**, and the same seven as `SWEEP_LOOKBACK_MS` on
  purpose. ADR 0005 already tied them together — "Adherence is reported by day
  and reviewed by week, so a sweep that reaches a week back can always account
  for every day a person is looking at" — and its Consequences section says what
  a wider period would cost: an unanswered Occurrence older than the lookback is
  never minted as Missed and never will be, because the bound is measured from
  the current instant each time. So a thirty-day figure counts the doses someone
  answered and silently omits the ones they did not, across exactly the absences
  where the difference matters. That is the flattering number the sweep removed,
  reappearing past day seven. A 7/30/90 selector was considered and rejected on
  that ground; a period the sweep can vouch for end to end has no such failure
  mode, which is worth more than a longer chart.

  Built from `Date`'s local calendar fields, not `now - days * 86_400_000`. The
  epoch version lands an hour off midnight either side of a clock change, which
  clips or doubles the first day of the window and leaves that day's bucket
  disagreeing with every other one. Joins the 100% gate; both mutants killed.

- `test(shared): the two calculator rules the ACs name and no case pinned` — no
  production change, both were already true. The daily buckets now have to sum
  back to the overall totals, stated as the invariant rather than one arithmetic
  example, because that is precisely what the C# got wrong:
  `AdherenceCalculator.cs:24`'s `dayTotal = g.Count()` counted Snoozed while the
  overall total did not, so the days added up to more than the whole. And day
  grouping is pinned across both US transitions — the suite runs at `TZ=UTC`,
  where `toISOString().slice(0, 10)` passes happily and ships a breakdown that
  blames Tuesday's misses on Monday for every western offset.

- `feat(mobile): sweep, then read, then compute` — `src/adherence/read.ts`. The
  order is the ticket. `app/(app)/index.tsx` renders the timeline first and
  sweeps after, deliberately, because the rows a sweep settles were already on
  screen as `pending`, which is what they were. Adherence has no such reading:
  a figure computed before the sweep is wrong in the flattering direction, and
  there is no intermediate state worth showing because the intermediate state is
  the bug. Putting the sweep inside the read rather than in the screen is what
  makes that structural — a second surface cannot forget it, because there is no
  way to obtain the figure that skips it.

  A failed sweep fails the whole read rather than being swallowed. Computing on
  top of a sweep that did not finish produces the flattering number silently,
  and the figure looks like an answer either way; "couldn't load" and a retry
  beats a confident 100%. The read also does **not** narrow its query by
  `reminderKind`, though the filter exists and would read fewer rows —
  medication-only is a rule, it is stated once in the calculator, and stating it
  again here would put a second copy somewhere no test covers.

- `feat(mobile): the surface` — `app/(app)/adherence.tsx`, reached from the home
  footer. The headline percentage, the four counts, and the day-by-day
  breakdown, each day with a bar as well as its two figures so a bad day is
  findable at a glance rather than by reading seven lines.

  **Snoozed gets its own count and its own sentence.** It lowers the figure the
  way a miss would, because a dose deferred is a dose not yet taken — but it is
  not reported *as* a miss, because the person made a decision rather than
  forgetting. Folding it into `missed` would have been one fewer row and a small
  lie; leaving it out of the summary would leave the arithmetic not adding up,
  which is worse here than anywhere else in the app.

  The empty state says "nothing to report yet" rather than rendering 0%, which
  has the same shape as a week in which every dose was missed. Somebody opening
  this screen on their first day has not failed at anything.

- `feat(mobile): two formatting rules that could each tell a lie` —
  `format/percent.ts` reserves the top end: `Math.round` turns 99.6% into
  "100%", so a person who missed a dose is told they missed none, on the one
  surface built so the number could be trusted. Only the top end, deliberately —
  the mirror case looks symmetrical and is not, because nudging 0.4% up to "1%"
  reports adherence nobody achieved, which is this sequence's own failure mode
  in miniature. Understating gives a person something to act on; overstating is
  the thing being fixed. The count beside the figure ("1 of 250") is what says a
  dose was taken. The guard is justified by this function's own input domain and
  not by what its caller currently produces: today's only caller cannot reach it,
  because an imperfect week needs 200 doses before it rounds to 100 — but a
  formatter that is correct only because of what happens to be upstream is a bug
  waiting for the upstream to change. Its case says so as a property rather than
  as two convenient inputs: across the domain, "100%" comes back for exactly one
  value. `format/datetime.ts` gains `dayKeyToLocalMs`, because reading a
  `"YYYY-MM-DD"` bucket key back with `new Date(key)` parses it as **UTC**
  midnight and renders every day as the day before it for every zone behind
  Greenwich. Both are covered, and every mutant of both was killed.

### 2026-08-07 — Type a phrase, get the right Reminder

Closes #15. The natural-language parser has been in this repository since the
port — deterministic, offline, 100% covered, ported line for line from
`ReminderParser.cs` with its ten xUnit cases translated — and it has had zero
callers outside its own test file the entire time. `packages/shared/src/parser`
is 1,377 lines of engine that nothing ever started. This ticket is a screen and
about 170 lines of glue, and it makes capture work for both Kinds at once, which
is why the spec calls it the primary creation surface.

- `feat(shared): the reviewable draft between a phrase and a Reminder` — a
  `ReminderDraft` is deliberately *not* a `Reminder`: no id, no owner, no
  timestamps, because none of the three is the parser's to guess. Three rules
  live in `parser/draft.ts` so the screen holds none of them.

  `readPhrase` **always returns a draft**, including for the two inputs the
  parser refuses (no tokens at all; no token that could serve as a name). That is
  what makes "a phrase that cannot be parsed falls back to entering the Reminder
  directly" structural rather than a branch someone has to remember to write —
  there is no state in which the entry surface has nothing to show. The fallback
  keeps the typed phrase as the name and takes **no** schedule at all: the parse
  reached that arm without producing a reminder, so nothing it saw about *when*
  is worth keeping, and `none` puts the reminder in the Backlog until a person
  gives it a time. A guessed time would have been the one failure mode a
  medication reminder must not have.

  `withKind` drops `dosage`, `pillsRemaining`, `pillsPerDose`,
  `refillReminderDays` and `rxcui` when a draft becomes a task. Kind detection
  is the parser's most confident guess and its most visible mistake — "urgent
  call the surgery about the biopsy result" reads as a medication, because past
  three meaningful words the grammar assumes a drug name — so correcting it is
  the likeliest correction there is. Hiding those fields behind a
  `kind === "task"` check in the form would still have *saved* them: a task with
  a 20mg dose.

  `withName` drops the `rxcui`, because an RxNorm concept id is not an
  independent field — it asserts that *this name* is that concept. Pick
  "Metformin Hydrochloride 500 MG Oral Tablet" from the lookup, shorten it to
  "Metformin", and without this the row saves claiming an identity for a name
  nobody verified. `rxcui` lives on the draft rather than in the screen's own
  state precisely so both rules can be functions with cases behind them; held in
  the surface, "a task carries no drug identity" would have been a
  `kind === "task"` check in a screen, which is the extraction being incomplete.

  The module joins the 100% coverage gate, and all 26 mutants of it were killed.

- `feat(mobile): one box that produces either Kind` — `app/(app)/add.tsx`, in
  two stages. Type; then review a form, not a confirmation dialog. Every field
  the parser filled in is the field you correct, in place, and "Edit phrase"
  goes back with the phrase still in the box. What the parser could not place is
  named on screen ("Not used: …") rather than dropped, because a phrase parses
  *around* those tokens — without saying so, a word that had no effect looks
  exactly like a word that did.

  The supply counts open by themselves when the phrase named any of them, and
  sit behind one tap when it named none. A misread is not only a number read
  wrongly — it is also "30 pills" that landed in the unused words — so a form
  with no route to that number would make the parser's silence unappealable;
  three empty count boxes on arrival would be the six-field form this surface
  exists to replace.

  It is the primary creation surface rather than the first of seven identical
  footer links, so `+ Add` is the one that gets the button. `/tasks/new` and the
  medication form stay reachable: this screen falls back to a form of its own
  for a phrase it cannot read, but retiring a working surface is not this
  ticket's to do.

  It is the first caller to pass `priority` to `remindersRepo.create`, which
  that module's `enqueueExternalWrite` comment had flagged as needing provenance
  the schema does not carry. The exposure is bounded and named at the top of the
  screen: it applies only when an enqueue fails after its row write, only to
  `priority`, which no scheduling decision reads (ADR 0003 derives Tier from
  Kind), and the device keeps the chosen value regardless. Discarding a typed
  "urgent" to avoid it would have been the larger lie.

- `refactor(mobile): drug search becomes a component, schedules get one voice` —
  `meds/search.tsx` was a screen, and a screen is exactly one entry point. The
  entry surface needs the same RxNorm search while holding a draft, and a round
  trip through the router would either lose the draft or serialize it into route
  params and rebuild it — a second definition of what a draft is. `DrugSearch`
  now takes an `onPick`; the search screen keeps the only part that was ever
  route-specific, which is where a pick goes. `describeSchedule` moves out of
  `meds/index.tsx` to `src/format/schedule.ts` and takes a `ScheduleTimes`
  rather than a whole `Reminder`, because the review card needs the same
  sentence and a draft is not a Reminder. It stays in `apps/mobile`: it reaches
  for the device locale and time zone, which is what a replayable package must
  not do.

### 2026-08-07 — The app asks whether it may remind you

Closes #20, and it is the most consequential thing fixed since the sweep. The
app's entire purpose is delivering a medication reminder on time; for anyone who
installed it and did not go looking through a debug screen, it delivered none —
silently, with no error, no empty state and no warning. `requestPermissions()`
had exactly one caller in the whole codebase, `app/(app)/debug/notifications.tsx`
line 78, and nothing on any real path called it. The alarm fired at the armed
millisecond, notify-kit ran, and the framework threw
`SecurityException: POST_NOTIFICATIONS permission is not granted` and dropped the
notification. The person found out by missing doses.

**Every check in place said delivery worked, and each was right about what it
measured.** The device harness passed 9/9 because it grants the permission at
startup (constraint 12) and because opening the debug screen requests it as a
side effect. `docs/poc-results.md` criterion #5 was a genuine PASS — the alarm
really does fire. It just never asserted that a notification reaches somebody.
That distinction is now the shape of the case that guards it.

- `feat(notifications): ask on a path a person actually walks` — a
  `NotificationPermissionProvider` in the signed-in tree, prompting once per run
  and re-reading on every transition to `active`. Gated on `isSignedIn` because
  the issue asks for the request "not on cold start before there is anything to
  be reminded about", and a fresh install's cold start *is* the sign-in screen.
  The foreground re-read is the whole answer to "a denial reversed later from
  Settings is picked up without reinstalling", and it is the same trigger the
  reschedule and the missed sweep already use, for the same reason.

  `asked` is a ref, deliberately not persisted. It exists only to stop the app
  asking twice in one run; storing it across runs would be a cached permission
  verdict, which is the exact shape of the POC stub that todo 6 of
  `.omo/plans/convex-expo-revisit.md` forbids by name.

- `feat(notifications): a refusal that says so` — a banner above the Today
  timeline. Two severities and they are different sentences: `blocking` means
  nothing reaches you, `degraded` means it reaches you late. Painting both red
  would teach people to scroll past the one that means something — which is why
  the exact-alarm gap, real since Phase 1 and never rendered anywhere but the
  debug screen, gets its own wording rather than borrowing this one's. Each
  carries the button that resolves it: the OS prompt, the per-app notification
  settings, or the alarm settings, and the platform is re-read on return.

- `feat(shared): the two decisions live where a test can run them` —
  `shouldRequestNotificationPermission` and `derivePermissionGap` widen
  `permission-state.ts` rather than inventing a second representation, as the
  issue asks. `asked` is not derivable from the snapshot and that is the point:
  `undetermined` before the prompt and `undetermined` after a dismissed prompt
  are the same platform state and call for opposite behaviour. The module joins
  the 100% coverage gate — a defect that is silent by construction is one a
  branch can be added to without anybody noticing.

- `fix(shared): an unrecognised authorization is not permission` —
  `derivePermissionStatus` had a `default:` arm reading an out-of-union value as
  authorized. Unreachable through the mobile mapper, and wrong in the same
  direction as every other defect in this area: it would have gone on believing
  it held a permission nobody granted. It now reads as `undetermined`, which
  asks.

- `test(harness): device case 12, and the only case that revokes the grant` —
  from POST_NOTIFICATIONS revoked, its `user-set`/`user-fixed` flags cleared,
  and notify-kit's own `permission_requested` flag cleared. All three are
  needed, and none of them alone is a fresh install; see constraint 14 below for
  what each one costs to get wrong. The case launches through the dev-client
  entry URL — no deep link, no debug screen — waits for the system dialog to
  appear on its own, allows it, and then asserts that a reminder seeded through
  the production scheduler **posts**. Not that an alarm is armed. It is excluded
  from `--all` for the reason it exists: it removes the grant every other
  delivery case depends on.

  "The app reads the platform rather than a constant" is settled by the two
  steps together rather than by a reading of its own — a hardcoded `true` would
  never have raised the dialog, and a hardcoded `false` could not report
  `authorized` afterwards. That is not a weaker check than asking the app
  directly; it is the only one available, because asking is what spends the
  prompt.

- `fix(harness): give case 12 the no-device bail its siblings did not need` —
  `fresh_app_process` ends in `wait_for_app_ready`, which polls to a 180s
  deadline for an answer a detached device will never give. Cases 8 and 9 reach
  the same helper and get away with it only because nothing runs them without a
  device; `verify-harness-guards.sh` runs this one. Verified by mutation — with
  the bail removed the guard script hung past two minutes, inside a gate that is
  meant to be device-free. `restore_device` now re-grants the permission too:
  every path out of case 12 that is not the happy one leaves it revoked, and a
  phone left silently unable to show a reminder because a test was cancelled is
  the same class of harm as the airplane mode that check 5c guards.

Four of the five things the review found were in code written to prevent exactly
their own failure mode, which is worth recording rather than quietly fixing:

- **The prompt could re-open on top of itself.** `asked` was set from the
  resolved result of `requestPermissions()`. On Android the dialog is its own
  activity, so dismissing it resumes the host and RN emits AppState `active` —
  which re-entered the provider while the request was still awaiting, read
  `asked === false`, and raised the dialog again. A prompt somebody cannot get
  away from is worse than the missing prompt this whole entry is about. The gate
  now takes an `onPrompt` callback that fires immediately *before* the dialog
  goes up. The case pinning it had to be rewritten once: the first version made
  the mutation recurse until the runner crashed, and both branches ended at
  `undetermined`, so the assertion could not tell a second dialog from none.

- **Case 12 read silence from the app as a product FAIL.** `pm revoke` kills the
  package, so the status deep link went into a process still re-bundling from
  Metro; an empty reply then composed into "THE APP DOES NOT READ THE PLATFORM"
  — the loudest claim in the file — written into a committed artifact. This is
  the same shape as the false verdict `verify-harness-guards.sh` exists after,
  arriving from a direction that guard does not cover: the device is present, so
  `add_result`'s choke point lets it through. The read that produced it is gone
  entirely (constraint 14), and the branch with it.

- **A "the app never asked" verdict could be about an app that never ran.** The
  restart's return value was discarded, so a dev client that never finished
  bundling produced no dialog for the same 180s as an app that does not ask. The
  case now proves the app was alive — and reports what it answered — before it
  will call the absence of a prompt a defect. That check earned itself on the
  mutation run, where it correctly recorded `pending_count=59` beside the FAIL.

- **The blocking banner named Android, on a string reachable from iOS**, and its
  button called notify-kit's `openNotificationSettings`, which is a no-op off
  Android — a dead end under the words "Reminders are turned off". The copy in
  `@taskrx/shared` names no operating system, which is the guardrail that keeps
  platform concerns behind `src/notifications/`, and the mobile adapter falls
  back to `Linking.openSettings()`.

Gates: `npm run verify` — 519 tests across three workspaces, 159 migration
checks, harness guards. Every new assertion was mutation-checked separately, 13
mutations, each caught by the case naming it.

**Verified on the Pixel 8 Pro / Android 17**, and the case was proven able to
fail before its pass was believed. Against the shipped code it passes: prompt on
screen from a cold launch, Allow, both sides reading granted, and a reminder
seeded through the production scheduler POSTING. Against a build with
`shouldRequestNotificationPermission` forced to `false` — the pre-#20 app — it
fails with "THE APP NEVER ASKED", and correctly reports that the app was running
throughout rather than blaming a dead process. Evidence:
`.omo/evidence/issue-20-case-12-permission.json`.

**Constraint 14, and it is why the first run of case 12 reported a defect that
was not there.** `pm revoke` plus `pm clear-permission-flags` does NOT reproduce
a fresh install: notify-kit records that it has asked in the app's own
SharedPreferences (`app.notifee.core.xml`, `permission_requested`), and derives
NOT_DETERMINED from it — the upstream #1237 fix this app depends on. No `pm`
command reaches that file. With the flag set the app reads `denied` on a
permission nobody was ever asked about and correctly declines to prompt, because
Android will not show a dialog for a spent permission.

The case had also set that flag itself. Its "what does the app believe" pre-read
went in through a deep link, the deep link mounted the provider, the provider
raised the dialog, and the next deep link brought the harness activity in front
of it — spending the prompt during setup, unobserved, and then reporting the app
for not asking. The pre-read is gone: the never-asked state is now established
AND verified entirely from outside the app (`run-as` clears the flag, and the
file is read back to check), and the app is asked what it believes only after
the dialog has been answered, when asking costs nothing. "Not a hardcoded
boolean" is settled by the two steps instead — a constant `true` would never
have raised the dialog, and a constant `false` could not report `authorized`
afterwards.

**Not closed here.** The remaining finding from 2026-08-03 — the
post-notification SQLite lock — is still untracked and unfixed.

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
