# TaskRx Architecture

TaskRx is a native Kotlin Android app that keeps its data in SQLite on the device and syncs it to a self-hosted Convex backend. Sign-in is Clerk. The reasons for this stack are in `docs/specs/kotlin-and-self-hosted-convex.md`. Earlier lines of work are preserved on branches: `archive/dotnet-maui` (.NET MAUI + ASP.NET Core) and `archive/expo-rn` (React Native + Expo, with the TypeScript domain package `packages/shared` that `apps/android/core` was ported from).

## High-level diagram

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Android app (apps/android)        │         │ Convex (infra/convex)        │
│                                    │         │  self-hosted, on Postgres    │
│  ┌──────────────────────────────┐  │         │                              │
│  │ Room / SQLite (source of     │  │         │ Schema:                      │
│  │  truth on device)            │  │         │  - reminders                 │
│  │  - reminders                 │  │         │  - reminderInstances         │
│  │  - reminder_instances        │  │         │                              │
│  │  - sync_queue + dead_letter  │  │         │ Functions (packages/convex): │
│  └──────────────────────────────┘  │         │  - reminders.{list,create..} │
│           │                        │         │  - reminderInstances.{...}   │
│           ▼                        │         │  - rxnorm.search (Action)    │
│  ┌──────────────────────────────┐  │         │                              │
│  │ :core (pure Kotlin/JVM)      │  │         │ Clerk JWT verifier:          │
│  │  - schedule + horizons       │  │         │  - enforces userId on all    │
│  │    (64 doses, 32 tasks)      │  │         │    reads/writes              │
│  │  - timeline, sweep planner   │  │         └──────────────┬───────────────┘
│  │  - adherence, refill, parser │  │                        │
│  │  - conflict rules, retry     │  │                        │
│  │  - permission-state          │  │                        │
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
│  Clerk (auth) ─────────────────────┼───────────────────────▶│
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘
```

The app is split into two Gradle modules. `:core` is pure Kotlin/JVM with no Android imports and holds every domain rule; Kover gates it at 100% line and branch coverage. `:app` is the Android application: Room storage, notifications, Compose screens, sign-in and sync. A rule never lives in `:app`.

## Data-flow rules

1. **Local SQLite is the source of truth on device.** All reads and writes go through repositories that hit Room first.
2. **Convex is the source of truth in the cloud.** Sync pushes local changes up and pulls remote changes in.
3. **The notification scheduler reads from SQLite, never from Convex.** This is why reminders fire offline.
4. **Schedule computation is pure and deterministic.** Given a reminder config and the current time, `:core` returns the next N occurrences. No storage calls inside it.
5. **Notification horizon is global, not per-reminder, and there is one per Tier.** `selectMedicationHorizon` picks the 64 soonest occurrences across ALL active *medication* reminders; when both A@21:00 and B@10min-from-now exist, B always wins over A regardless of what A already had scheduled. `selectTaskHorizon` does the same for tasks on a separate budget of 32. The two read disjoint sets of Reminders, so neither can shorten the other (ADR 0003), and they are armed through different OS subsystems, so neither can exhaust the other's capacity (ADR 0007). A full task budget costs a dose nothing.
6. **The sync queue drains until MAX_ATTEMPTS (5) failures; then dead-letters.** Dead-lettered entries are retained (never deleted) for inspection and manual requeue.
7. **Conflict resolution rules live in one tested place.** `core/sync/Conflict.kt`: local wins ties, and a locally `completed` instance is never overwritten by a remote pull.
8. **An unanswered Occurrence becomes Missed by a sweep, not by a query.** Instances are minted lazily, so a moment nobody answers has no row at all. The sweep planner in `:core` (`core/sweep/Planner.kt`) decides which moments have run out of time; `MissedSweepRunner` in `:app` persists the verdicts, serialized so two foregrounds cannot mint one moment twice. The Grace Period (2h) and lookback (7 days) are ADR 0005.
9. **A Reminder with no Schedule never reaches the timeline.** `none` produces no Occurrences, so the Backlog screen is rendered from `reminders` rows; completing one mints its Instance at the moment of the act, with `scheduledTime = completedAt` (ADR 0002).

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
| `schedule` | TEXT | JSON `ScheduleTimes` — `daily`, `interval`, `weekly`, `once` or `none` |
| `priority` | TEXT | `critical`, `high`, `normal`, `low`. Read by no scheduling decision |
| `tags` | TEXT | JSON string array |
| `pills_remaining` | INTEGER | Refill tracking, nullable |
| `pills_per_dose` | REAL | Refill tracking, nullable |
| `refill_reminder_days` | INTEGER | Refill tracking, nullable |
| `caregiver_ids` | TEXT | JSON string array |
| `active` | INTEGER | 1 = active |
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
| `entity_type` | TEXT | `reminder` or `reminder_instance` |
| `entity_id` | TEXT | Local entity ID |
| `operation` | TEXT | `create`, `update`, or `delete` |
| `payload` | TEXT | JSON snapshot at enqueue time |
| `attempts` | INTEGER | Retry counter |
| `last_error` | TEXT | Last failure message |
| `created_at` | INTEGER | Unix ms |
| `dead_letter` | INTEGER | 0 = pending; 1 = terminally failed after MAX_ATTEMPTS. Filtered out of `pending()`; retained for inspection and requeue. |

## Convex schema

See `packages/convex/convex/schema.ts`.

- `reminders` mirrors the local `reminders` rows, keyed by `userId`.
- `reminderInstances` mirrors the local instance rows, with the parent named by its Convex document id.
- Soft deletes use `deletedAt` on Convex; local rows are marked inactive or hard-deleted depending on the flow.
- The `schedule` validator accepts the five variants (`daily`, `interval`, `weekly`, `once`, `none`) and rejects malformed payloads.

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
- `shouldApplyRemoteReminderInstance(local, remote)`: false when local is `completed` (never overwritten), or when the reminder rule above would return false. True otherwise.

The truth table, including the safety-critical completed-instance preservation, is covered by `:core` tests and sits under the Kover gate.

## Notification flow

### Scheduling

1. User saves a medication → repo writes to SQLite and enqueues a sync entry.
2. `NotificationScheduler` reads all active reminders from Room.
3. Each Tier's desired set is computed from its own budget: `selectMedicationHorizon(reminders, now, 64)` returns the 64 soonest occurrences **across all medication reminders**, and `selectTaskHorizon(reminders, now, 32)` the 32 soonest across all tasks. Both are global rather than per-reminder, so a reminder with a closer next occurrence always displaces one with a farther next occurrence regardless of how many triggers the farther reminder already has. Neither selection sees the other Kind's reminders at all.
4. **Diff, don't rebuild — in one pass over both Tiers:** read the currently armed ids from both Tiers, compare against the union of the two desired sets:
   - Cancel only the notifications that are in neither desired set.
   - Create only the notifications that are missing, each with its own Tier's trigger.
   - Deterministic IDs `${reminderId}-${scheduledTime}` for a dose and `task-${reminderId}-${scheduledTime}` for a task make the diff safe. The namespaces must stay disjoint: the diff sees both Tiers' armed ids, so a pass that knew about only one Tier would cancel the other's as stale.
   - Idempotent: two consecutive reschedules with unchanged data cancel and create nothing.
5. A dose is armed with `AlarmManager.setAlarmClock` (`AlarmClockGateway`); see the decision below. A task is armed as a WorkManager `OneTimeWorkRequest` (`WorkRequestGateway`); see ADR 0007. `RearmReceiver` re-arms both Tiers after boot.
6. When a notification fires and the user taps it, `TapHandler` opens the answer screen with the reminder id and `scheduledTime` (`core/notifications/Tap.kt`). It is Kind-neutral: a task tap lands on the same answer screen, which already speaks both Kinds.
7. Answering writes a Reminder Instance to SQLite and enqueues sync.

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

**Deliberately NOT declared: `USE_EXACT_ALARM`.** Google Play restricts it to apps whose core function is an alarm clock or calendar; medication reminders are not a verifiably approved category, and declaring it is a Play-review liability. `setAlarmClock` works with `SCHEDULE_EXACT_ALARM` alone.

The pure derivation lives in `core/notifications/PermissionState.kt`: whether to prompt (`shouldRequestNotificationPermission`) and what to say when the answer leaves reminders undeliverable (`derivePermissionGap`).

Neither half is allowed to be silent. A `blocking` gap (POST_NOTIFICATIONS refused or never granted) and a `degraded` one (exact alarms off) both render as a banner above the Today timeline, each with the button that resolves it. The remedy routes to the OS prompt, the per-app notification settings, or the alarm settings, and the platform is re-read afterwards. Device case 12 asserts the whole chain from a revoked, never-asked state through to a notification actually posting.

## Testing

- `apps/android/core`: JUnit 5 tests run at `TZ=UTC`, with Kover enforcing 100% line and branch coverage on the gated classes (`./gradlew :core:test :core:koverVerify`). They include the DST cases for `core/time/LocalDay.kt`.
- `apps/android/app`: JVM unit tests with Robolectric and in-memory Room (`./gradlew :app:testDebugUnitTest`). They cover the reschedule diff and which Tier each Kind is armed through, the push worker, the missed sweep's persistence pass, the Adherence and Refill reads, and the permission gate. They never load the Convex native library.
- `packages/convex`: `convex-test` cases run against an in-memory deployment (`npm test`), including the schedule validator's variants and the malformed payloads it must reject.
- `apps/android/scripts/notification-harness.sh`: an adb-driven bash script. `--all` runs the nine local-only cases (killed-app, `setAlarmClock` primitive in use, Doze, exact-alarm-denied fallback, boot reschedule, 30-med stress, weekly schedule, task-Tier delivery, task flood vs dose), `--auth` the signed-in ones, and case 12 is signed-in and excluded from `--all` because it revokes the POST_NOTIFICATIONS grant every other delivery case depends on. It emits JSON to `.omo/evidence/`. See `docs/poc-results.md` for the verdicts and the rig preconditions (the app must be signed out, and cases 3 and 5 need separate invocations because each leaves the keyguard up).
- The harness deep link `taskrx:///harness/<action>/<id>/<delayMs>/<nonce>` and the code behind it exist only in debug builds (`app/src/debug`). Notifications are local, so the harness works on a fresh install without signing in.

The gate commands are in the README.

## Security

- Clerk issues JWTs after email-code sign-in. The Convex deployment verifies them and enforces `userId` on all reads and writes.
- No secrets are committed. Backend secrets live in the server's environment (see `infra/convex`); the app's `CONVEX_URL` and `CLERK_PUBLISHABLE_KEY` come from the ignored `apps/android/local.properties`.
- Release builds refuse cleartext HTTP; only the debug manifest allows it.
- The harness deep link exists only in debug builds, so it is not in a release APK.

## Deferred

- iOS. `:core` has no Android imports so Kotlin Multiplatform stays possible.
- A web client.
- Drug interaction checking.
- Push notifications (currently local-only; caregiver alerts via FCM deferred).
