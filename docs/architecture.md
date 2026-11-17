# TaskRx Architecture

TaskRx is a native Kotlin Android app that keeps its data in SQLite on the device and syncs it to a self-hosted Convex backend. Sign-in is Clerk. The reasons for this stack are in `docs/specs/kotlin-and-self-hosted-convex.md`. The earlier React Native + Expo app is preserved on the `archive/expo-rn` branch (with the TypeScript domain package `packages/shared` that `apps/android/core` was ported from).

## High-level diagram

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Android app (apps/android)        │         │ Convex (infra/convex)        │
│                                    │         │  self-hosted, on Postgres    │
│  ┌──────────────────────────────┐  │         │                              │
│  │ Room / SQLite (source of     │  │         │ Schema:                      │
│  │  truth on device)            │  │         │  - reminders                 │
│  │  - reminders                 │  │         │  - reminderInstances         │
│  │  - reminder_instances        │  │         │  - notices                   │
│  │  - notices                   │  │         │  - watchSources              │
│  │  - watch_sources             │  │         │  - mailboxState              │
│  │  - sync_queue + dead_letter  │  │         │                              │
│  └──────────────────────────────┘  │         │ Functions (packages/convex): │
│           │                        │         │  - reminders.{list,create..} │
│           ▼                        │         │  - reminderInstances.{...}   │
│  ┌──────────────────────────────┐  │         │  - notices / watchSources    │
│  │ :core (pure Kotlin/JVM)      │  │         │  - rxnorm.search (Action)    │
│  │  - schedule + horizons       │  │         │  - mailbox.poll (cron, JMAP) │
│  │    (64 doses, 32 tasks)      │  │         │                              │
│  │  - timeline, sweep planner   │  │         │ Clerk JWT verifier:          │
│  │  - adherence, refill, parser │  │         │  - enforces userId on all    │
│  │  - conflict rules, retry     │  │         │    reads/writes              │
│  │  - permission-state          │  │         └──────────────┬───────────────┘
│  │  - notice dates + proposals  │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ Android notification APIs    │  │                        │
│  │  - AlarmManager.             │  │                        │
│  │    setAlarmClock for doses   │  │                        │
│  │  - WorkManager for tasks     │  │                        │
│  │  - diff-based reschedule     │  │                        │
│  │  - boot receiver re-arms     │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │  HTTPS (Convex client) │
│  ┌──────────────────────────────┐  │                        │
│  │ PushWorker / PullSync        │──┼───────────────────────▶│
│  │  - drains sync_queue         │  │                        │
│  │  - retries + dead-letter     │  │◀────── realtime ───────│
│  │  - live subscriptions        │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  ┌──────────────────────────────┐  │                        │  ┌──────────────────────────────┐
│  │ Notice roads (:app)          │  │                        ├──│ Mailbox poll (Convex cron)   │
│  │  - share sheet               │  │                        │  │ JMAP → watched senders only  │
│  │  - notification listener     │  │                        │  └──────────────────────────────┘
│  │  - NoticeIntake funnel       │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  Clerk (auth) ─────────────────────┼───────────────────────▶│
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘                        │
                                                 Fastmail (api.fastmail.com)
```

The app is split into two Gradle modules. `:core` is pure Kotlin/JVM with no Android imports and holds every domain rule; Kover gates it at 100% line and branch coverage. `:app` is the Android application: Room storage, notifications, Compose screens, sign-in and sync. A rule never lives in `:app`.

## Data-flow rules

1. **Local SQLite is the source of truth on device.** All reads and writes go through repositories that hit Room first.
2. **Convex is the source of truth in the cloud.** Sync pushes local changes up and pulls remote changes in.
3. **The notification scheduler reads from SQLite, never from Convex.** This is why reminders fire offline.
4. **Schedule computation is pure and deterministic.** Given a reminder config and the current time, `:core` returns the next N occurrences. No storage calls inside it.
5. **Notification horizon is global, not per-reminder, and there is one per Tier.** `selectMedicationHorizon` picks the 64 soonest occurrences across ALL active *medication* reminders; when both A@21:00 and B@10min-from-now exist, B always wins over A regardless of what A already had scheduled. `selectTaskHorizon` does the same for tasks on a separate budget of 32. The two read disjoint sets of Reminders, so neither can shorten the other (ADR 0003), and they are armed through different OS subsystems, so neither can exhaust the other's capacity (ADR 0007). A full task budget costs a dose nothing.
6. **The sync queue drains until MAX_ATTEMPTS (5) failures; then dead-letters.** Dead-lettered entries are retained (never deleted) for inspection and manual requeue.
7. **Conflict resolution rules live in one tested place.** `core/sync/Conflict.kt`: local wins ties, a row with a change not yet pushed is never overwritten by a pull, and a locally `completed` instance is replaced only by a newer remote `pending`, the person's own undo (ADR 0008).
8. **An unanswered Occurrence becomes Missed by a sweep, not by a query.** Instances are minted lazily, so a moment nobody answers has no row at all. The sweep planner in `:core` (`core/sweep/Planner.kt`) decides which moments have run out of time; `MissedSweepRunner` in `:app` persists the verdicts, serialized so two foregrounds cannot mint one moment twice. The Grace Period (2h) and lookback (7 days) are ADR 0005.
9. **A Reminder with no Schedule never reaches the timeline.** `none` produces no Occurrences, so the Backlog screen is rendered from `reminders` rows; completing one mints its Instance at the moment of the act, with `scheduledTime = completedAt` (ADR 0002).

## State machines

Every lifecycle in the app is explicit and pure, in `:core` where more than one surface acts
on it. Surfaces read the same functions instead of re-deriving a status or a Kind check.
ADR 0011 is the catalog, with diagrams.

| Lifecycle | States | Module |
|---|---|---|
| Reminder Instance | pending, snoozed, missed, completed, skipped | `core/domain/InstanceLifecycle.kt` |
| Reminder | active, paused(window), stopped | `core/domain/ReminderLifecycle.kt` |
| Sync queue entry | waiting, dead-lettered | `core/sync/QueueLifecycle.kt` |
| Notification arming | desired, armed exact/degraded, fired | `core/notifications/Reschedule.kt` |
| Permission gate | undetermined, authorized, partial, denied | `core/notifications/PermissionState.kt` |
| Session | loading, signed out, signed in | `auth/AuthGateway.kt`, `Session.kt` |
| As-needed dose | none yet, waiting (gap/limit), ready, window passed | `core/asneeded/AsNeeded.kt` |
| Refill warning | unprojectable, outlook; raised, standing, retired | `core/refill/Projection.kt`, `Reconcile.kt` |
| Missed sweep | no row, pending, snoozed-expired → missed | `core/sweep/Planner.kt` |

`InstanceLifecycle` and `ReminderLifecycle` answer two questions apart: the actions a person
is offered for a state, and the rewrites the store accepts. `ANSWERABLE_STATUSES` and
`UNDOABLE_STATUSES` are derived from the Instance table, and `ReminderInstancesRepository`
refuses any status change the table does not have.

## Local SQLite schema

Room entities in `apps/android/app/src/main/kotlin/com/taskrx/mobile/data/db/Entities.kt`. There is one fresh schema. The Kotlin app has no migration from the Expo app's database, because that app never shipped a release build or held user data.

### `reminders`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 generated on device |
| `user_id` | TEXT | Clerk user ID |
| `kind` | TEXT | `medication` or `task` — the axis the POC shape lacked |
| `name` | TEXT | Display name |
| `rxcui` | TEXT | Optional RxNorm CUI. Storage-only; not on the domain type |
| `dosage` | TEXT | Optional free text |
| `instructions` | TEXT | Optional free text |
| `schedule` | TEXT | JSON `ScheduleTimes` — `daily`, `interval`, `weekly`, `monthly`, `once` or `none` |
| `priority` | TEXT | `critical`, `high`, `normal`, `low`. Read by no scheduling decision |
| `tags` | TEXT | JSON string array |
| `pills_remaining` | INTEGER | Refill tracking, nullable |
| `pills_per_dose` | REAL | Refill tracking, nullable |
| `refill_reminder_days` | INTEGER | Refill tracking, nullable |
| `caregiver_ids` | TEXT | JSON string array |
| `active` | INTEGER | 1 = active |
| `pauses` | TEXT | JSON array of `{from, until?}` windows (ADR 0009). `[]` when never paused. Added in database version 2 |
| `overrides` | TEXT | JSON array of `{from, to, noticeId?}`: one occurrence moved off the moment its Schedule produced (ADR 0013). `[]` when never moved. Added in database version 3 |
| `notice_rules` | TEXT | JSON array of `{sourceKind, sourceValue, terms}`: which notices may be proposed against this reminder. `[]` when none |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge |

### `reminder_instances`

One row per occurrence the user acted on, or that the sweep judged missed.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 generated on device |
| `reminder_id` | TEXT | FK to `reminders(id)` ON DELETE CASCADE |
| `user_id` | TEXT | Clerk user ID |
| `reminder_kind` | TEXT | Denormalized from the parent so adherence filters without a join |
| `scheduled_time` | INTEGER | Unix ms — the instant this instance occupied |
| `status` | TEXT | `pending`, `completed`, `snoozed`, `skipped`, `missed` |
| `completed_at` | INTEGER | Unix ms, nullable |
| `snoozed_until` | INTEGER | Unix ms, nullable |
| `snooze_count` | INTEGER | Defaults to 0 |
| `notes` | TEXT | Optional |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge |

`completed` is what a medication-only model would call "taken", and
`completed_at` is the ingestion timestamp on a medication. Both Kinds share this
table, and a task is never taken — see ADR 0001. The UI still *displays*
"Taken" for a medication.

### `sync_queue`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | |
| `entity_type` | TEXT | `reminder`, `reminder_instance`, `watch_source` or `notice` |
| `entity_id` | TEXT | Local entity ID |
| `operation` | TEXT | `create`, `update`, or `delete` |
| `payload` | TEXT | JSON snapshot at enqueue time |
| `attempts` | INTEGER | Retry counter |
| `last_error` | TEXT | Last failure message |
| `created_at` | INTEGER | Unix ms |
| `dead_letter` | INTEGER | 0 = pending; 1 = terminally failed after MAX_ATTEMPTS. Filtered out of `pending()`; retained for inspection and requeue. |

### `notices`

One row per outside message a watched source admitted.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 on device, or derived from the email id for the mailbox road |
| `user_id` | TEXT | Clerk user ID |
| `source_kind` | TEXT | `email`, `app` or `share` |
| `source_value` | TEXT | Sender address, package name, or the share's own id |
| `title` | TEXT | The subject line, or the notification's title |
| `sentence` | TEXT | The kept sentence(s); nullable. The body itself is never stored |
| `received_at` | INTEGER | Unix ms |
| `external_id` | TEXT | De-duplication key: JMAP email id, `notification:<pkg>:<key>:<postTime>`, share hash. Nullable |
| `resolution` | TEXT | `none`, `applied` or `dismissed` |
| `resolved_at` | INTEGER | Unix ms, nullable |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge |

### `watch_sources`

The senders and apps whose notices may be kept at all. A share is never one.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 on device |
| `user_id` | TEXT | Clerk user ID |
| `kind` | TEXT | `email` or `app` |
| `value` | TEXT | Sender address (lower-cased) or package name |
| `label` | TEXT | Display name |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge |

## Convex schema

See `packages/convex/convex/schema.ts`.

- `reminders` mirrors the local `reminders` rows, keyed by `userId`. `pauses` is an optional array of `{from, until?}` windows. `overrides` is an optional array of `{from, to, noticeId?}` (ADR 0013). `noticeRules` is an optional array of `{sourceKind, sourceValue, terms}`. `schedule` is a union, one member of which is `{type: "asNeeded", minGapMinutes, maxPer24h?}` (ADR 0010).
- `reminderInstances` mirrors the local instance rows, with the parent named by its Convex document id.
- `notices` holds a notice's narrow retention — source, subject, one to two sentences, received time, de-duplication id, resolution. `by_user_external_id` is the de-duplication lookup, which is why the mailbox poll and the device's intake can share one key.
- `watchSources` is the gate on what is kept at all, and it syncs *because* the server-side mailbox poll filters by it.
- `mailboxState` is a single row holding the mailbox poll's cursor. One mailbox for the deployment (D15), so one cursor.
- Soft deletes use `deletedAt` on Convex; local rows are marked inactive or hard-deleted depending on the flow.
- The `schedule` validator accepts every variant the device can express — `daily`, `interval`, `weekly`, `monthly` (`daysOfMonth`, 1 to 31), `once`, `none` and `asNeeded` — and rejects malformed payloads.

The functions are TypeScript, the only TypeScript product code in the repo. They are tested with `convex-test` against an in-memory deployment (`npm test`) and deployed to the self-hosted backend with `infra/convex/deploy.sh`.

## Sync implementation

### Push (`PushWorker`)

1. Triggered on app foreground and when connectivity returns. One drain runs at a time.
2. Drains `sync_queue` oldest first, excluding dead-lettered rows.
3. For each entry, calls the matching Convex mutation with the payload, through `ConvexGateway` (the seam the tests fake).
4. On success: deletes the queue entry and marks the source row `sync_status = 'synced'`.
5. On error: increments `attempts`, applies exponential backoff (1s to 30s cap), and after 5 failed attempts marks the source row `sync_status = 'error'` and the queue entry `dead_letter = 1`. A dead-lettered entry is not processed again until it is requeued.
6. A push refused for want of a token spends no attempt; the app logs in again and retries.

The retry policy and the Convex call shapes live in `:core` (`core/sync/Policy.kt`, `core/sync/Push.kt`).

### Pull (`PullSync`)

Live subscriptions to `reminders.list` and `reminderInstances.list` mirror changes into Room. Reminders are applied first and awaited, then each instance resolves its parent through the reminder's `convex_id` before it is written. One coordinator does both halves rather than two independent ones: an instance names its parent by Convex document id, and splitting the halves raced the parent write and stranded the rows it skipped. `core/sync/Badge.kt` derives the `synced | pending | syncing | offline | error` badge.

### Conflict resolution (`core/sync/Conflict.kt`)

Two pure functions consumed by both repository upsert paths:

- `shouldApplyRemoteReminder(local, remote)`: false when `local != null && local.updatedAt >= remote.updatedAt` (local wins ties). True otherwise.
- `shouldApplyRemoteReminderInstance(local, remote, localHasUnpushedChange)`: false when the local row has a change not yet pushed; false when local is `completed` unless the remote is a newer `pending` (an undo, ADR 0008); true when remote is `completed`; otherwise last-write-wins with local winning ties.

The truth table, including the safety-critical completed-instance preservation, is covered by `:core` tests and sits under the Kover gate.

## External notices

Outside messages — a share, an app notification, an email — can *propose* a move or a new
task. Nothing applies itself; ADR 0014 has the full reasoning and the retention decision.

```
   share sheet ─────┐
   app notification ─┼──▶ NoticeIntake (device) ──▶ notices (Room) ──▶ sync ──▶ notices (Convex)
   mailbox (JMAP) ───┘            │                                              │
                                  │ watched? → dedup? → keep a sentence           │ pull
                                  ▼                                              ▼
                          Notice + Proposal ──▶ person answers ──▶ OccurrenceOverride
```

**The device funnel** (`app/notices/NoticeIntake.kt`) is the one place every road ends. In
order: normalise the source value and check it against `watch_sources`; de-duplicate on
`external_id`; trim title and body; choose the kept sentence with `core/notices/Intake.kt`;
write the notice and enqueue a push. It never parses a date — the extractor runs when the
Proposals screen reads the row, so there is one implementation.

**The notification listener** (`app/notices/NotificationListener.kt`) records the package
name of *every* posted notification in a device-only seen list (`app/settings/SeenApps.kt`,
capped at 200, never synced) and reads title and text only for a package that is already a
watched app source. That is the whole reason for the two-step design: an unapproved app's
notification leaves nothing behind but its name. Android may withhold text, so a notice with a
title and no date is a normal state.

**The mailbox** (`packages/convex/convex/mailbox.ts`, on a five-minute cron in `crons.ts`) is
the only road that runs with no phone. It reads `JMAP_TOKEN` from the environment; absent, it
returns `no_token` and writes nothing. It asks Fastmail for messages received after the
`mailboxState` cursor (minus a ten-minute overlap), keeps only those from a watched sender,
and writes one notice per watching person, de-duplicated on the email id. **The server keeps
at most two spans containing a month name or a digit and does no date parsing** — the Kotlin
reader stays the only implementation. The body is discarded after the spans are chosen.

Proposals and the refusals live in `core/notices/Proposal.kt`: a notice may be applied to a
task by hand or by a rule, a move is an `OccurrenceOverride` (ADR 0013), medications never
move, and an answered, opened or past-grace occurrence never moves.

## Notification flow

### Scheduling

1. User saves a medication → repo writes to SQLite and enqueues a sync entry.
2. `NotificationScheduler` reads all active reminders from Room, plus the Instances already Completed or Skipped for a moment still ahead (a dose taken early). Those moments are left out of both desired sets, because the Schedule only predicts moments and cannot know they were answered. Each selection asks for that many extra first, so the budget stays full.
3. Each Tier's desired set is computed from its own budget: `selectMedicationHorizon(reminders, now, 64)` returns the 64 soonest occurrences **across all medication reminders**, and `selectTaskHorizon(reminders, now, 32)` the 32 soonest across all tasks. Both are global rather than per-reminder, so a reminder with a closer next occurrence always displaces one with a farther next occurrence regardless of how many triggers the farther reminder already has. Neither selection sees the other Kind's reminders at all.
4. **Diff, don't rebuild — in one pass over both Tiers:** read the currently armed ids from both Tiers, compare against the union of the two desired sets:
   - Cancel only the notifications that are in neither desired set.
   - Create only the notifications that are missing, each with its own Tier's trigger.
   - Deterministic IDs `${reminderId}-${scheduledTime}` for a dose and `task-${reminderId}-${scheduledTime}` for a task make the diff safe. The namespaces must stay disjoint: the diff sees both Tiers' armed ids, so a pass that knew about only one Tier would cancel the other's as stale.
   - Idempotent: two consecutive reschedules with unchanged data cancel and create nothing.
5. A dose is armed with `AlarmManager.setAlarmClock` (`AlarmClockGateway`); see the decision below. A task is armed as a WorkManager `OneTimeWorkRequest` (`WorkRequestGateway`); see ADR 0007. `RearmReceiver` re-arms both Tiers after boot.
6. When a notification fires and the user taps it, `TapHandler` opens the answer screen with the reminder id and `scheduledTime` (`core/notifications/Tap.kt`). It is Kind-neutral: a task tap lands on the same answer screen, which already speaks both Kinds.
7. Answering writes a Reminder Instance to SQLite and enqueues sync.

### The Today screen

Today has two tabs, one per Kind, and they answer different questions.

- **Medications** keeps the single-day timeline: a day bar that pages back without limit and forward to today, the day's rows in time order, the NEXT highlight on the first moment still to come, and a slot bar for doses due together. The day is `localDayBounds(now, dayOffset)`.
- **Tasks** is not one day. `sectionTasks` in `:core` (`core/tasks/TaskSections.kt`) splits the task rows of a wider window into **Overdue**, **Due today** and **Upcoming**. Overdue is every `missed` or past-due `pending` task, however old — an unanswered task does not expire with the sweep's seven-day lookback (ADR 0015) — one row per task at its latest such moment, most overdue first. Due today is the local day minus the rows Overdue took. Upcoming is tomorrow through day +7, grouped by local day. The tab has no day bar and no NEXT highlight, because neither means anything across a week. An Overdue row says "Overdue" (display-only: the stored status stays `pending` or `missed`) and carries inline Done and Skip, which answer through `BatchAnswers` with the task's own wording. The tab badge counts Overdue plus the answerable rows of Due today; Upcoming is ahead, so it is not a badge.
- **Catch up** is reached from the More menu, not a banner. It remains the place to clear every missed occurrence of a recurring task, including the older ones Overdue hides behind its latest-miss row.

### Answering doses together

Doses due at the same moment can be answered in one tap, from the Today screen or from the shade. `batchSlots` in `:core` (`core/batch/Batch.kt`) is the one rule: answerable medication rows that share a `scheduledTime`, two or more. Tasks never join; a task is not a dose.

- **Today:** a slot bar above the first row of each such moment, with Take all, Snooze all and Skip all.
- **Shade:** each dose still posts its own notification. When a post leaves two or more doses for one moment in the shade, `NotificationPoster` adds a `batch-<scheduledTime>` notification with the same three buttons. It is rebuilt from the notifications showing (their extras carry the reminder id and moment), not from the database, so alarms that fire in the same second converge on the full list. It never alerts.
- **One dose:** each medication notification carries Take, Snooze and Skip of its own, through the same receiver and `BatchAnswers` (a batch of one). Answering one rebuilds the batch notification from the doses left, or removes it when fewer than two remain. A task notification has no buttons.
- **Buttons:** each is a `PendingIntent` with its own data URI (`taskrx://batch/<time>/<action>`, or `taskrx://dose/<id>/<time>/<action>` for one dose), because a PendingIntent's identity ignores extras. `BatchActionReceiver` runs `BatchAnswers` with the app closed.
- **`BatchAnswers`** differs from the answer screen on purpose: a dose that already has an answer is left alone (the answer screen would mint a second pending Instance, double-counting it and its supply); one clock reading serves the batch so every snooze comes back together as a batch; the reschedule runs once at the end.

### The two channels

`medication_reminders` is HIGH importance so a dose bypasses Do Not Disturb and alerts audibly. `task_reminders` is DEFAULT: it sounds and appears in the shade, and does not push past DND or take over the screen. Separate channels also let a person silence their task reminders without touching their doses, which one shared channel would not allow. Both id strings are frozen — Android keys the user's per-channel overrides to them.

### Alarm decision: why `setAlarmClock`

`AlarmManager.setAlarmClock` is the strongest Android guarantee available for a scheduled notification. It is exact, wakes the device, bypasses Doze, and is the primitive the stock Clock app uses, so vendor aggressive-kill policies (Xiaomi MIUI, Oppo ColorOS, Huawei EMUI, Vivo FuntouchOS; see [dontkillmyapp.com](https://dontkillmyapp.com/)) generally respect it. `setExactAndAllowWhileIdle` is exact and survives Doze but has no such OEM resilience, and is the direct cause of the "reminder never fired" class of bug on those devices. Before the move to Kotlin this was reached through `react-native-notify-kit`; the app now calls the platform directly.

**Tradeoff (product decision):** `setAlarmClock` causes the system to render a persistent alarm-clock glyph in the status bar while any alarm is pending. With a 64-dose rolling horizon there is always at least one pending, so the icon is effectively permanent. For a medication reminder app this is judged acceptable and arguably a feature: it visibly signals that reminders are armed.

**Degradation:** if `SCHEDULE_EXACT_ALARM` is not granted, the app surfaces that as a `degraded` permission gap rather than crashing or dropping the reminder. Device case 4 covers the fallback.

### Permission model

Permissions declared in `apps/android/app/src/main/AndroidManifest.xml`:

- `POST_NOTIFICATIONS` (Android 13+ runtime permission). Requested on the signed-in path, once per run, and re-read on every foreground. Without it Android discards every notification, silently.
- `SCHEDULE_EXACT_ALARM` (user-granted special app access, following the documented Android 14+ lifecycle: declare, check `canScheduleExactAlarms()`, send the user to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, recheck, reschedule, degrade if denied).
- `RECEIVE_BOOT_COMPLETED` (the boot receiver re-arms both Tiers).
- `WAKE_LOCK`, `INTERNET`, `ACCESS_NETWORK_STATE`.
- **Notification access** (`BIND_NOTIFICATION_LISTENER_SERVICE`, granted through
  `ACTION_NOTIFICATION_LISTENER_SETTINGS`) for the notification-listener road. It is a
  user-granted special access with no runtime check API; `PermissionPlatform.notificationListenerAllowed()`
  reads the enabled-listeners list fresh every time. Without it the listener road is inert.

**Deliberately NOT declared: `USE_EXACT_ALARM`.** Google Play restricts it to apps whose core function is an alarm clock or calendar; medication reminders are not a verifiably approved category, and declaring it is a Play-review liability. `setAlarmClock` works with `SCHEDULE_EXACT_ALARM` alone.

The pure derivation lives in `core/notifications/PermissionState.kt`: whether to prompt (`shouldRequestNotificationPermission`) and what to say when the answer leaves reminders undeliverable (`derivePermissionGap`).

Neither half is allowed to be silent. A `blocking` gap (POST_NOTIFICATIONS refused or never granted) and a `degraded` one (exact alarms off) both render as a banner above the Today timeline, each with the button that resolves it. The remedy routes to the OS prompt, the per-app notification settings, or the alarm settings, and the platform is re-read afterwards. Device case 12 asserts the whole chain from a revoked, never-asked state through to a notification actually posting.

## Testing

- `apps/android/core`: JUnit 5 tests run at `TZ=UTC`, with Kover enforcing 100% line and branch coverage on the gated classes (`./gradlew :core:test :core:koverVerify`). They include the DST cases for `core/time/LocalDay.kt`.
- `apps/android/app`: JVM unit tests with Robolectric and in-memory Room (`./gradlew :app:testDebugUnitTest`). They cover the reschedule diff and which Tier each Kind is armed through, the push worker, the missed sweep's persistence pass, the Adherence and Refill reads, and the permission gate. They never load the Convex native library.
- `packages/convex`: `convex-test` cases run against an in-memory deployment (`npm test`), including the schedule validator's variants and the malformed payloads it must reject, and the mailbox poll end to end against a stubbed JMAP endpoint (watched sender stored, unwatched dropped, replayed poll not stored twice, sentence spans chosen without parsing).
- `apps/android/scripts/notification-harness.sh`: an adb-driven bash script. `--all` runs the nine local-only cases (killed-app, `setAlarmClock` primitive in use, Doze, exact-alarm-denied fallback, boot reschedule, 30-med stress, weekly schedule, task-Tier delivery, task flood vs dose), `--auth` the signed-in ones, and case 12 is signed-in and excluded from `--all` because it revokes the POST_NOTIFICATIONS grant every other delivery case depends on. Case 14 (`notice_intake`) is signed-in and drives a notice through the real intake: stored, then a duplicate, then a different one stored. It emits JSON to `.omo/evidence/`. See `docs/poc-results.md` for the verdicts and the rig preconditions (the app must be signed out, and cases 3 and 5 need separate invocations because each leaves the keyguard up).
- The harness deep link `taskrx:///harness/<action>/<id>/<delayMs>/<nonce>` and the code behind it exist only in debug builds (`app/src/debug`). Notifications are local, so the harness works on a fresh install without signing in.

The gate commands are in the README.

## Security

- Clerk issues JWTs after email-code sign-in. The Convex deployment verifies them and enforces `userId` on all reads and writes.
- No secrets are committed. Backend secrets live in the server's environment (see `infra/convex`); the app's `CONVEX_URL` and `CLERK_PUBLISHABLE_KEY` come from the ignored `apps/android/local.properties`.
- Release builds refuse cleartext HTTP; only the debug manifest allows it.
- The harness deep link exists only in debug builds, so it is not in a release APK.
- **Notices put outside sentences in the backend.** A notice's kept sentence syncs like a reminder, so the Convex database and its offsite backups hold it (ADR 0014). The retention is narrow — source, subject, one to two sentences — and the body is discarded before the write.
- **Notification access is a broad permission.** The listener receives every notification; it stores a package name from each, and reads text only from an app the person approved. The Settings copy says so.
- **The mailbox token** (`JMAP_TOKEN`) is a deployment-wide environment variable, never in the database, and the poll is a no-op without it. One mailbox serves the whole deployment (D15), which the UI states.

## Deferred

- iOS. `:core` has no Android imports so Kotlin Multiplatform stays possible.
- The desktop Companion (`apps/desktop`, Rust on Rune) is decided but not built: it shows and
  answers, never delivers, and keeps data in memory only (ADR 0016); its rules are a Rust port
  held to `:core` by golden fixtures (ADR 0017); and two writers need an Instance id derived
  from its Occurrence (ADR 0018), which changes the phone first. The phone also gains a
  periodic background pull and a fail-open check at alarm time, so a desktop answer or edit
  reaches its alarms (ADR 0016).
- A web client.
- Drug interaction checking.
- Push notifications (currently local-only; caregiver alerts via FCM deferred).
- A forward-to-TaskRx email address for notices; the three intake roads cover where notices
  arrive (ADR 0014). A per-user mailbox, too — this deployment has one mailbox (D15).

### Taking a medication as needed

A medication can be taken **as needed** (ADR 0010): the Schedule variant `AsNeededTimes(minGapMinutes, maxPer24h?)`, which has no Occurrences. `asNeededStatus` in `:core` reads the moments its doses were taken (Completed Instances) and says when the next is allowed. Today shows each as a card at the top of the Medications tab only once a dose is allowed, and for an hour after that moment; before then it stays off the screen, and the card carries a single Take button. Log a dose shows when each as-needed medication was last taken and when the next is allowed. The notification scheduler arms one "you can take another dose" prompt at that moment (id `ready-<reminderId>-<readyAt>`, Medication Tier) and arms nothing once it has passed. Its Take button records a dose through `DoseRecorder`; Skip writes nothing. Adherence leaves these medications out.

### Pausing

A medication can be paused (ADR 0009) from the Reminders screen: from now or a later day, until a chosen day or until the person resumes. A pause is a window on the Reminder (`pauses`), and `nextOccurrences` leaves out every moment inside one, so the Horizon, the timeline, the Missed sweep, Catch up and the refill projection all agree without each knowing about it. Pausing and resuming reschedule the notifications straight after, like Stop. Resuming ends the window at that moment rather than deleting it, so the sweep's seven-day lookback does not turn the paused doses into Missed ones.

### Recording a dose nobody scheduled

"+ Dose" on Today opens a list of every medication (stopped ones last). Picking one records a Completed Reminder Instance at a time today, defaulting to now; a time that has not happened yet is refused. The timeline already shows an Instance the Schedule did not predict. There is no "unscheduled" flag, so such a dose counts as Completed toward Adherence like any other.
