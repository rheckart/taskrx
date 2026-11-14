# External notices: letting outside events move and create tasks

Status: planned (not started). Written 2026-11-13 from a planning conversation.

Two pains, one mechanism:

1. A date moves for a reason the app cannot know — a holiday bumps trash collection a
   day, and the waste company says so by email, Facebook or a text.
2. A deadline arrives in email and there is no task yet to answer it with.

Neither is a case for reading the calendar or guessing. Both are cases for **bringing the
outside sentence into the app, proposing what it means, and letting the person decide**.

## Steps

- [x] Verify the ground: self-hosted Convex cron runs, VPS reaches Fastmail JMAP, token answers `Email/query`
- [x] Occurrence overrides in `:core` (type, engine, tests) and "Move this occurrence" on a task row
- [ ] Notices and watched sources as data: Convex tables, Room migration 3, sync, Notices screen, share intake
- [ ] Date extraction and proposals in `:core`, apply path, heads-up notification, count
- [ ] Notification listener: service, seen list, approval, intake
- [ ] Mailbox: JMAP action, cron, cursor, sentence selection, tests
- [ ] Docs: ADR 0014, `architecture.md`, competitor document amendment (ADR 0013 and the `CONTEXT.md` terms are done)

## Scope check

The uncommitted `CONTEXT.md` scope section says a feature belongs if it helps a commitment
happen on time or records honestly that it did or did not. A notice that says "collection
moved to Wednesday" does exactly the first. The app still never decides *whether* the move
is right; it proposes it and records the person's answer. Nothing here adjudicates or
measures.

`docs/competitor-gap-analysis.md` lists email-to-task under "gaps I would not close"
("integrations for a task app; none has a medication analogue"). That line is superseded
by this plan for the task side only, and the document should say so.

## Decisions taken in the planning conversation

| # | Decision | Why |
|---|---|---|
| D1 | Three intake roads in v1: **share sheet**, **notification listener**, **mailbox polled on the server** | The three ways a notice actually reaches a person. A forward-to-TaskRx address is deferred; a webhook is anticipated. |
| D2 | **Propose everything.** Nothing is applied without confirmation | Matches the app's existing rule that nothing is saved until it has been seen (`ui/add/AddScreen.kt`). |
| D3 | A move is a **per-occurrence override on the Reminder**, applied inside `nextOccurrences` | Same single-point shape as `pauses` (ADR 0009): the Horizon, the timeline, the sweep, Catch up and the Tasks tab all agree without learning about it. |
| D4 | Overrides and moves are **tasks only** | A medication schedule is a clinical instruction. Moving a dose because an email said so is the app acting on health information it cannot verify. |
| D5 | A move is **refused** once the occurrence is answered or past its Grace Period | Nothing rewrites what already happened. The proposal instead offers "create a separate task" or dismiss. |
| D6 | The date reader handles **absolute dates only**, never relative words, never guesses a year | A wrong date on a commitment is the failure this app exists to avoid. |
| D7 | Ambiguous numeric dates (`05/06`) are **refused**, not guessed | Same reason. Month names and unambiguous numerics still read. |
| D8 | Rules are authored **on the reminder** ("Notices about this") | The question "what could move this?" has one answer, next to Pause. |
| D9 | **Watched sources** (sender address, app package) gate whether a notice is stored at all; a rule on a reminder gates a proposed move | Without this, a deadline email from a new sender would be dropped before the person could ever see it — which is pain #2. |
| D10 | Proposals are confirmed in a **Notices screen** with a count, plus a heads-up notification when a proposal arrives | One place holds every decision, and no date is applied from a shade button without it in front of you. |
| D11 | A recurring task's move picks the **latest unsettled occurrence at or before the date named**, else the earliest one after | Deterministic and showable ("Tue 18 Nov 07:00 → Wed 19 Nov 07:00"), with a way to choose a different occurrence. |
| D12 | Notification-listener apps are approved from a **seen list** (package names only until approved) | The listener technically receives everything; it must hold no text from an unapproved app. |
| D13 | A notice can become a task **by hand**, and can be applied to a task **by hand**, with no rule | A rule is only the pre-filled version of the same action. |
| D14 | Retention: sender, subject, **the matched sentence**, extracted date, received time — **synced** in a Convex `notices` table, both roads | Server polling makes sync unavoidable: a notice fetched and discarded on the server cannot reach a phone that is not running. Revises the earlier "never synced" answer. The body is discarded after matching. |
| D15 | **One mailbox per deployment**, JMAP token in the Convex environment | Matches how the VPS is run (a personal backend) and keeps a live mail credential out of the database. |

## Language to add to `CONTEXT.md`

- **Notice**: An outside message — a share, a notification, an email — that a watched source
  admitted and TaskRx kept a sentence of. It is evidence, not a commitment.
  _Avoid_: Message, Alert, Feed item
- **Watched source**: A sender address or an app whose notices TaskRx may keep. The gate on
  what is stored at all.
  _Avoid_: Subscription, Integration, Connector
- **Proposal**: What TaskRx thinks a Notice means for one Reminder, awaiting the person's
  answer. Never applied on its own.
  _Avoid_: Suggestion, Suggestion engine, Auto-fix
- **Occurrence override**: The record that one Occurrence of a task moved to a different
  moment. The schedule is unchanged; this week's moment is.
  _Avoid_: Exception, Skip, Reschedule (a reschedule edits the rule; this moves one moment)

## The model

### `:core` — occurrence overrides

New `core/domain/OccurrenceOverride.kt`, modelled on `core/domain/Pause.kt` (wire JSON,
validation, empty-list default) because that is the closest existing thing:

```kotlin
data class OccurrenceOverride(
    val from: Long,       // the moment the Schedule produced
    val to: Long,         // where it now falls
    val noticeId: String? = null,  // why, when a Notice caused it
)
```

`Reminder.overrides: List<OccurrenceOverride> = emptyList()`, additive with a default, exactly
as `pauses` was added in database version 2. `createReminder` gains the parameter.

Application point: inside `core/schedule/Occurrences.kt`, map each produced `fireAt` through
the overrides **before** the `<= fromMs` exclusion and before pausing/count logic sees it.
That ordering matters:

- A source moment that has passed but whose target is still ahead must be emitted at the
  target, so the Horizon arms it and the sweep can still settle it.
- A source moment inside a pause is never produced, so its target never appears. A pause
  still wins.
- `nextOccurrences` re-sorts and de-duplicates after mapping, because a move can reorder the
  batch and two sources can land on one target.
- `count` may be short after de-duplication; the existing documentation already allows
  "fewer than count when the schedule cannot supply that many".
- An override whose source the schedule no longer produces (because the person edited the
  schedule) is inert. It stays in the array — a few entries a year — and explains itself if
  the log ever asks.

Validation (pure, in `:core`): `to > from`, `to` not in the past at creation time, the
Reminder's Kind is `task`, and `to` not inside a pause window. The Convex mutation checks
Kind too, since the validator cannot express a Kind-dependent field.

### `:core` — notices

- `core/notices/Source.kt`: normalising a source key (`EMAIL`: lowercased address;
  `APP`: exact package). One type for both roads.
- `core/notices/Extract.kt`: `readNoticeDate(text, now, zone)` returning either
  `ReadDate(moment, matchedText)` or `Unread(reason)`.
  - Month names: `Nov 18`, `November 18`, `18 November`, `18th of November`.
  - Numeric: only when one component exceeds 12, plus ISO `2026-11-18`.
  - Times: `5pm`, `5:30 pm`, `17:00`, `noon`, `midnight`.
  - No year: the next such date at or after `now`; the resolved date is always shown.
  - Nothing, or two competing dates: no date, and the screen says so.
- `core/notices/Proposal.kt`: `proposalsFor(notice, reminders, instances, now, zone)` —
  pure matching of a notice's source against each task's `noticeRules` (terms, case-folded),
  then the occurrence choice (D11) and the refusals (D5 plus: target in the past, target in
  a pause, schedule is `none`, no occurrence left).
  Each result carries the source moment, the target, and a refusal reason when it has one.
- A moved moment keeps the occurrence's **wall-clock time of day** unless the notice named a
  time. A new task from a notice defaults to `09:00`, the parser's `DEFAULT_TIME`.

`noticeRules` lives on the Reminder next to `pauses`: a JSON array of
`{sourceKind, sourceValue, terms?}`. Same codec discipline as `Pause.kt`.

### Data

Room, database version 2 → 3 (a `Migration`, never a destructive fallback — the file
already holds real rows):

| Table | Columns |
|---|---|
| `notices` | `id`, `user_id`, `source_kind` (`email`/`app`/`share`), `source_value`, `title`, `sentence`, `received_at`, `external_id`, `resolution` (`none`/`applied`/`dismissed`), `resolved_at`, `sync_status`, `convex_id` |
| `watch_sources` | `id`, `user_id`, `kind`, `value`, `label`, `created_at`, `sync_status`, `convex_id` |

`reminders` gains `overrides TEXT NOT NULL DEFAULT '[]'` and `notice_rules TEXT NOT NULL DEFAULT '[]'`.

Seen-but-unapproved packages are **not** a table: a `SharedPreferences` set in
`AppSettings`, device-only and never synced, capped at the last 200 package names.

Convex: `notices` and `watchSources` tables with `by_user`, `by_user_received`,
`by_user_external_id` indexes; `reminderFields` gains optional `overrides` and `noticeRules`
(optional so rows written before this stay valid, exactly as `pauses` is).

Sync: two new queue `entity_type`s (`notice`, `watch_source`) through `PushWorker`'s existing
`when` blocks, two new subscriptions in `PullSync` (watch sources first, then notices), and
`LocalAdoption` extended to reassign both tables — a signed-out notice must follow the person
who signs in, or it is stranded.

### Intake

One funnel, `:app` `notices/NoticeIntake.kt`, taking
`(sourceKind, sourceValue, title, body, receivedAt, externalId)`:

1. Is the source watched? If not, drop it — nothing is written.
2. De-duplicate on `externalId` (JMAP email id, notification key + `postTime`, share hash).
3. Run `readNoticeDate` on the body; keep at most the sentence the date came from (plus the
   one before it when the date sentence has no subject).
4. Write the `notices` row, enqueue a push, and let proposals be derived on read.

The three roads feed it:

- **Share** (`MainActivity`): an `ACTION_SEND` / `text/plain` intent filter, handing the
  shared text and subject to a new route that opens the intake for confirmation. Gmail's
  share usually carries body text; a notification usually does not.
- **Notification listener**: a `NotificationListenerService` declared with
  `BIND_NOTIFICATION_LISTENER_SERVICE`, granted via `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
  It records package names into the seen list always, and reads title/text only for approved
  packages. Android may withhold text (`EXTRA_TEXT` absent), so a notice from this road can
  arrive with a title and no date — which is a supported state, not an error.
- **Mailbox (server)**: see below. Nothing on the device speaks JMAP.

### Server (`packages/convex`)

- `mailbox.ts`, an internal action on a **cron every 5 minutes**: read `JMAP_TOKEN` from the
  environment; absent, no-op. Query Fastmail's JMAP session endpoint
  (`https://api.fastmail.com/jmap/session`), then `Email/query` for messages received after
  a stored cursor, `Email/get` with `bodyValues` for the plain-text body of each.
- Filter by watched senders (an internal query over `watchSources`, which is why that list
  syncs). A non-matching message is skipped and nothing is written.
- Keep at most two sentences: the server picks sentence-ish spans containing a month name or
  a digit, and stores those. It does **no** date parsing — the Kotlin extractor stays the
  only implementation, so the two cannot drift. Then write the `notices` row with
  `externalId = email id` and advance the cursor.
- `convex/crons.ts` registers the poll. Verify on the self-hosted backend first (Phase 0):
  crons are a backend scheduler feature, and this deployment has never run one.
- `notices.ts` and `watchSources.ts`: `list`, `create`, `update` (resolution), `remove`,
  each enforcing `getCurrentUserId` and ownership like `reminders.ts`.

Credential: one deployment-wide `JMAP_TOKEN` set with
`CONVEX_TARGET=vps ./deploy.sh env set JMAP_TOKEN …`. The UI says plainly that the mailbox
belongs to the deployment, so a second person on the same backend sees the first person's
notices unless a per-user mailbox is built (D15).

### UI

- **Notices screen** (`ui/notices/`), `Routes.NOTICES = "notices"`, entered from the More
  menu with the undecided count beside the label. Each row: source, subject, the kept
  sentence with the date it yielded marked, then the actions — `Apply to …` (a prefilled
  proposal when a rule matched, a task picker otherwise), `New task from this notice`,
  `Dismiss`.
- **Applying a move** shows both moments and the sentence, and can pick a different
  occurrence from the task's upcoming list.
- **Edit reminder** gains a "Notices about this" section: watched-source picker, optional
  match words, and the reminder's existing overrides with the reason each was made.
- **Settings** gains watched sources (add sender, approve a seen app) and a read-only
  mailbox line ("connected / not configured").
- **Notification** when a proposal arrives: one heads-up on the existing `task_reminders`
  channel (never a new channel — the channel ids are frozen), id `notice-<noticeId>`, posted
  from the pull path when a newly pulled notice has a proposal and has not pinged before.
- Optional polish, and worth it: a task row that was moved says "moved from Tue 7:00", read
  from the override; History shows the same.

## Ordered work

Each phase is independently shippable and testable.

**Phase 0 — verify the ground** (small)
Self-hosted Convex runs a cron; the VPS can reach `api.fastmail.com`; a Fastmail JMAP token
answers an `Email/query`. If crons do not run self-hosted, the mailbox phase becomes a
systemd timer curling an HTTP action, which also means opening port 3211 behind Traefik.

**Phase 1 — occurrence overrides, no intake** (the trash move by hand)
`:core` override type, engine change, tests; Room migration 3; Convex `overrides` field;
reminder form; a "Move this occurrence" action on a task row. This alone closes pain #1
without any notice machinery, and it is the risky part of the model, so it lands first.

**Phase 2 — notices as data, share road only**
Convex `notices` + `watchSources`; Room tables and migration; repositories, queue types,
pull subscriptions, adoption; the Notices screen; share intent filter; watched sources in
Settings and "Notices about this" on the reminder. No extraction yet: you set the date.

**Phase 3 — extraction and proposals**
`core/notices/*` with the date reader, rule matching, occurrence choice and refusals; the
proposal UI, the apply path writing an override, the heads-up notification, the count.

**Phase 4 — notification listener**
Service, seen list, approval UI, intake.

**Phase 5 — mailbox**
`mailbox.ts`, cron, cursor, sentence selection, Convex tests, a real Fastmail account for
testing.

**Phase 6 — docs**
ADR 0013 (occurrence overrides: one moment, not the rule), ADR 0014 (notices: what is
stored, what syncs, why the body is not). Update `CONTEXT.md`, `docs/architecture.md`
(data flow, new tables, the mailbox poll and its privacy consequence), and amend the
competitor document's "email-to-task" line.

## Files and areas

`:core` (100% Kover, so every branch needs a case)
- `core/domain/OccurrenceOverride.kt` (new), `core/domain/Reminder.kt` (two fields)
- `core/schedule/Occurrences.kt` (the application point), `core/schedule/Horizon.kt`,
  `core/timeline/Projection.kt`, `core/sweep/Planner.kt` (call sites only — behaviour comes
  through `nextOccurrences`, which is the point)
- `core/notices/{Source,Extract,Proposal}.kt` (new)

`:app`
- `data/db/{Entities,Daos,TaskRxDatabase}.kt` (version 3, migration)
- `data/{NoticesRepository,WatchSourcesRepository,SyncPayloads,LocalAdoption}.kt`
- `sync/{PushWorker,PullSync,ConvexGateway,ServerDocs}.kt`
- `notices/{NoticeIntake,NotificationListener,SeenApps}.kt` (new)
- `ui/notices/{NoticesScreen,NoticesViewModel}.kt` (new), `ui/Routes.kt`, `ui/TaskRxApp.kt`,
  `ui/today/TodayScreen.kt` (More menu), `ui/reminders/EditReminderScreen.kt`,
  `ui/settings/SettingsScreen.kt`, `MainActivity.kt` (share filter), `AndroidManifest.xml`
  (listener service)
- `notifications/{NotificationPoster,NotificationsModule}.kt` (the proposal ping)

`packages/convex`
- `convex/schema.ts`, `convex/notices.ts`, `convex/watchSources.ts`, `convex/mailbox.ts`,
  `convex/crons.ts`, tests beside each

`infra/convex`: README note for `JMAP_TOKEN`; nothing else if crons work.

## Tests and gates

- `:core`: the override engine against DST, pauses, reordering, collisions, a schedule edit
  orphaning an override, and the `count` shortfall; the extractor's accepted and refused
  forms; occurrence choice and every refusal reason. All under `:core:koverVerify`.
- `:app`: migration 2→3 over a populated in-memory database; intake de-duplication; the
  listener ignoring an unapproved package; share parse; queue and pull of both new entity
  types; adoption of a signed-out notice; the ping firing once per proposal.
- `packages/convex`: `convex-test` for ownership and resolution, plus `mailbox.poll` against
  a faked `fetch` (matching sender, non-matching sender, cursor advance, no token).
- Harness: a debug-only `harness/notice` action in `app/src/debug` to inject a notice, so
  the end-to-end path can be driven from `scripts/notification-harness.sh` without a real
  mailbox.
- `npm run verify` and the Gradle gate from `AGENTS.md`.

## Risks

- **The 100% Kover gate** covers `:core`, which is where the hardest new logic lives. Budget
  for the tests, not for the code.
- **Notification access** is a broad, user-granted permission and Play review can ask why it
  is needed. The seen-list design means the app receives everything an app posts before you
  approve it; the copy must say so plainly, and the service must keep nothing but a package
  name until approval.
- **Mail text now reaches the server and its offsite backups** (D14). That is a real change
  in the app's privacy posture and belongs in the ADR and in `docs/architecture.md`, not
  only in a commit message.
- **One mailbox for the deployment** (D15) is invisible until a second account signs in.
  The UI has to say so.
- **Ambiguous dates and locale** will simply miss some notices (D6, D7). That is the chosen
  trade; the screen must show "no date found" rather than a plausible wrong one.
- **Cron support on the self-hosted backend is unverified.** Phase 0 exists for that.
- **An override can orphan** when a schedule is edited. It is inert by construction, but the
  edit form should show and allow removing overrides so the list does not confuse the person.
- **Android may withhold notification text**, so that road produces titles without bodies;
  a notice with no date is a normal state.
- Adding UI text as Kotlin literals continues the existing pattern and leaves the i18n item
  in `docs/competitor-gap-analysis.md` untouched.

## Assumptions, explicit

- A moved occurrence keeps its wall-clock time of day unless the notice names a time.
- A new task from a notice defaults to 09:00 when no time was read.
- "Already answered or swept" means: an Instance exists at that moment, or the moment is
  past its Grace Period. Within the Grace Period, an unanswered moment may still be moved.
- Overrides are kept indefinitely; they are a handful of entries a year and they explain
  the log.
- The whole feature is opt-in: no watched sources, no mailbox token and no granted listener
  means nothing is read.
- Notice text is not encrypted at rest on the device beyond the platform's own file
  encryption; the app lock and SQLCipher items in the competitor document are separate and
  still open.
