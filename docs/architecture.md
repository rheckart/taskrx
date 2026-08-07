# TaskRx Architecture

This document describes the architecture of the TaskRx proof-of-concept. The Phase 1 revisit (2026-07-26) reworked the notification layer, sync queue termination, conflict resolution, and permission handling; sections below reflect the current state on `main`. The prior POC state is preserved on tag `poc-v0.1.0`, and the .NET MAUI + ASP.NET Core alternative that briefly held the stack is preserved on branch `archive/dotnet-maui` (tag `archive/dotnet-maui-v0`).

## High-level diagram

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Expo App (Android)                │         │ Convex Backend               │
│                                    │         │                              │
│  ┌──────────────────────────────┐  │         │ Schema:                      │
│  │ expo-sqlite (source of       │  │         │  - medications               │
│  │  truth on device)            │  │         │  - doseLogs                  │
│  │  - medications               │  │         │                              │
│  │  - dose_logs                 │  │         │ Functions:                   │
│  │  - sync_queue + dead_letter  │  │         │  - medications.{query,...}   │
│  └──────────────────────────────┘  │         │  - doseLogs.{query,...}      │
│           │                        │         │  - rxnorm.search (Action)    │
│           ▼                        │         │                              │
│  ┌──────────────────────────────┐  │         │ Clerk JWT verifier:          │
│  │ @taskrx/shared               │  │         │  - enforces userId on all    │
│  │  - scheduleEngine (daily,    │  │         │    reads/writes              │
│  │    interval)                 │  │         └──────────────┬───────────────┘
│  │  - selectMedicationHorizon   │  │                        │
│  │    (global 64, doses only)   │  │                        │
│  │  - selectTaskHorizon         │  │                        │
│  │    (separate 32, tasks only) │  │                        │
│  │  - conflict resolution       │  │                        │
│  │  - permission-state derive   │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ react-native-notify-kit      │  │                        │
│  │  - SET_ALARM_CLOCK for doses │  │                        │
│  │  - WorkManager for tasks     │  │                        │
│  │  - diff-based reschedule     │  │                        │
│  │  - degrades gracefully on    │  │                        │
│  │    permission denial         │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │  HTTPS (Convex client) │
│  ┌──────────────────────────────┐  │                        │
│  │ syncWorker.ts                │──┼───────────────────────▶│
│  │  - drains non-dead-lettered  │  │                        │
│  │  - retries up to 5 attempts  │  │◀────── realtime ───────│
│  │  - dead-letters after that   │  │                        │
│  │  - listens for connectivity  │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  Clerk (auth) ─────────────────────┼───────────────────────▶│
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘
```

## Data-flow rules

1. **Local SQLite is the source of truth on device.** All reads/writes go through repos that hit SQLite first.
2. **Convex is the source of truth in the cloud.** Sync pushes local changes up and pulls remote changes in.
3. **The notification scheduler reads from SQLite, never from Convex.** This is why reminders fire offline.
4. **Schedule computation is pure + deterministic.** Given a med config + current time, `@taskrx/shared` returns the next N dose times. No DB calls inside the engine.
5. **Notification horizon is global, not per-reminder — and there is one per Tier.** `selectMedicationHorizon` picks the 64 soonest occurrences across ALL active *medication* reminders — iOS-cap-safe and, when both A@21:00 and B@10min-from-now exist, B always wins over A regardless of what A already had scheduled. `selectTaskHorizon` does the same for tasks on a separate budget of 32. The two read disjoint sets of Reminders, so neither can shorten the other (ADR 0003), and they are armed through different OS subsystems, so neither can exhaust the other's capacity (ADR 0007). A full task budget costs a dose nothing.
6. **Sync queue drains until MAX_ATTEMPTS (5) failures; then dead-letters.** Dead-lettered entries are retained (never deleted) for inspection and manual requeue.
7. **Conflict resolution rules live in one tested place.** `@taskrx/shared/sync/conflict` — local wins ties, and a locally `completed` instance is never overwritten by a remote pull.
8. **An unanswered Occurrence becomes Missed by a sweep, not by a query.** Instances are minted lazily, so a moment nobody answers has no row at all. `planMissedSweep` in `@taskrx/shared` decides which moments have run out of time; `apps/mobile/src/sweep/runner.ts` persists the verdicts, serialized so two foregrounds cannot mint one moment twice. The Grace Period (2h) and lookback (7 days) are ADR 0005.
9. **A Reminder with no Schedule never reaches the timeline.** `none` produces no Occurrences, so the Backlog surface (`app/(app)/backlog`) is rendered from `reminders` rows; completing one mints its Instance at the moment of the act, with `scheduledTime = completedAt` (ADR 0002).

## Local SQLite schema

Defined by `apps/mobile/src/db/migrations/004_unified_reminders.sql` and amended by `008_sync_reconcile_indexes.sql`, `009_drop_legacy_generation.sql` and `010_kind_neutral_instance_status.sql`. Migrations 001-003 created the legacy `medications` / `dose_logs` pair that 009 dropped; they are kept so an upgrading install still replays its history.

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

A `medications` / `dose_logs` pair preceded these two and was deleted with the
rest of the legacy generation (migration 009).

### `sync_queue`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | |
| `entity_type` | TEXT | `medication` or `dose_log` |
| `entity_id` | TEXT | Local entity ID |
| `operation` | TEXT | `create`, `update`, or `delete` |
| `payload` | TEXT | JSON snapshot at enqueue time |
| `attempts` | INTEGER | Retry counter |
| `last_error` | TEXT | Last failure message |
| `created_at` | INTEGER | Unix ms |
| `dead_letter` | INTEGER | 0 = pending; 1 = terminally failed after MAX_ATTEMPTS. Filtered out of `pending()`; retained for inspection and requeue. Added by migration 003. |

## Convex schema

See `packages/convex/convex/schema.ts`.

- `medications` — mirrors local medication rows, keyed by `userId`.
- `doseLogs` — mirrors local dose logs, with `medicationId` as a reference to the Convex `medications` table.
- Soft deletes use `deletedAt` on Convex; local rows are hard-deleted or marked inactive depending on the flow.

## Sync implementation

### Push (`apps/mobile/src/sync/worker.ts`)

1. Triggered on app foreground and on `NetInfo` reconnect.
2. Drains `syncQueueRepo.pending()` (excludes dead-lettered rows).
3. For each queue entry, calls the matching Convex mutation with the payload.
4. On success: deletes the queue entry and marks the source row `sync_status = 'synced'`.
5. On error: increments `attempts` via `markFailed`, applies exponential backoff (1s→30s cap), and after 5 failed attempts:
   - Marks the source row `sync_status = 'error'`.
   - Marks the queue entry `dead_letter = 1` via `syncQueueRepo.markDeadLettered`.
   - Stops re-processing the entry on subsequent drains.
6. Heartbeat: pings Convex every 30s when foregrounded to fast-path the queue.

Dead-letter recovery: `syncQueueRepo.deadLettered()` returns terminal rows for a debug UI; `syncQueueRepo.requeue(id)` clears the flag + resets `attempts` so the worker will retry.

### Pull (`apps/mobile/src/sync/pull.ts`)

Realtime hooks subscribe to Convex queries and mirror changes into SQLite:

- `useUnifiedSync(userId)` — one hook covering both unified tables. It subscribes to `api.reminders.list` and `api.reminderInstances.list`, applies the reminders first and awaits them, then resolves each instance's parent through `remindersRepo.localIdForConvexId` before writing. Deliberately one hook and not two: an instance names its parent by Convex document id, and splitting the halves across two effects both raced the parent write and stranded the rows it skipped.
- `useConvexSyncStatus()` — returns `'synced' | 'pending' | 'offline'` for the UI badge.

### Conflict resolution (`packages/shared/src/sync/conflict.ts`)

Two pure functions consumed by both repo `upsertMany` paths:

- `shouldApplyRemoteReminder(local, remote)` — false when `local != null && local.updatedAt >= remote.updatedAt` (local wins ties). True otherwise.
- `shouldApplyRemoteReminderInstance(local, remote)` — false when local is `completed` (never overwritten), or when the reminder rule above would return false. True otherwise.

11 vitest cases cover the truth table incl. the safety-critical completed-instance preservation, and `conflict.ts` sits under the shared package's 100% coverage gate.

## Notification flow

### Scheduling

1. User saves a medication → repo writes to SQLite and enqueues a sync entry.
2. `rescheduleAll(userId)` reads all active reminders from SQLite.
3. Each Tier's desired set is computed from its own budget: `selectMedicationHorizon(reminders, now, 64)` returns the 64 soonest occurrences **across all medication reminders**, and `selectTaskHorizon(reminders, now, 32)` the 32 soonest across all tasks. Both are global rather than per-reminder, so a reminder with a closer next occurrence always displaces one with a farther next occurrence regardless of how many triggers the farther reminder already has. Neither selection sees the other Kind's reminders at all.
4. **Diff, don't rebuild — in one pass over both Tiers:** read current pending trigger IDs from notify-kit, compare against the union of the two desired sets:
   - Cancel only the notifications that are in neither desired set.
   - Create only the notifications that are missing, each with its own Tier's trigger.
   - Deterministic IDs `${reminderId}-${scheduledTime}` for a dose and `task-${reminderId}-${scheduledTime}` for a task make the diff safe. The namespaces must stay disjoint: `getTriggerNotificationIds()` returns both subsystems' triggers, so a pass that knew about only one Tier would cancel the other's as stale.
   - Idempotent: two consecutive `rescheduleAll` calls with unchanged data cancel and create nothing.
5. A dose trigger is built with `AlarmType.SET_ALARM_CLOCK` — see the AlarmType decision below. A task trigger is built with `alarmManager: false`, which schedules it through WorkManager instead; see ADR 0007, including why omitting that key gives an exact alarm rather than none.
6. When a notification fires and the user taps it, the tap handler navigates to `/dose/log` with the reminder id and `scheduledTime`. It is Kind-neutral: a task tap lands on the same answer screen, which already speaks both Kinds.
7. Answering writes a Reminder Instance to SQLite and enqueues sync.

### The two channels

`medication_reminders` is HIGH importance so a dose bypasses Do Not Disturb and alerts audibly. `task_reminders` is DEFAULT: it sounds and appears in the shade, and does not push past DND or take over the screen. Separate channels also let a person silence their task reminders without touching their doses, which one shared channel would not allow. Both id strings are frozen — Android keys the user's per-channel overrides to them.

### AlarmType decision — why `SET_ALARM_CLOCK`

`SET_ALARM_CLOCK` is the strongest Android guarantee available for a scheduled notification. Per notify-kit's own AlarmType guide:

| AlarmType | Exact | Wakes | Doze bypass | OEM-resilient | Status icon |
|---|---|---|---|---|---|
| `SET` | ✗ | ✓ | ✗ | ✗ | ✗ |
| `SET_AND_ALLOW_WHILE_IDLE` | ✗ | ✓ | ✓ | ✗ | ✗ |
| `SET_EXACT` | ✓ | ✓ | ✗ | ✗ | ✗ |
| `SET_EXACT_AND_ALLOW_WHILE_IDLE` | ✓ | ✓ | ✓ | **✗** | ✗ |
| `SET_ALARM_CLOCK` | ✓ | ✓ | ✓ | **✓** | ✓ |

Vendor aggressive-kill policies — Xiaomi MIUI, Oppo ColorOS, Huawei EMUI, Vivo FuntouchOS (see [dontkillmyapp.com](https://dontkillmyapp.com/)) — generally respect `setAlarmClock` because it is the same primitive the stock Clock app uses. The pre-Phase-1 choice, `SET_EXACT_AND_ALLOW_WHILE_IDLE`, has no such resilience and is the direct cause of the "reminder never fired" class of bug on those OEMs.

The user has empirically verified `AlarmManager.SetAlarmClock` firing on a physical Android 17 device via the native MAUI build on `archive/dotnet-maui`; the Phase 1 change adopts the same primitive from the JS side.

**Tradeoff (product decision):** `SET_ALARM_CLOCK` causes the system to render a persistent alarm-clock glyph in the status bar while any trigger is pending. With a 64-dose rolling horizon there is always at least one pending, so the icon is effectively permanent. For a medication reminder app this is judged acceptable and arguably a feature — it visibly signals to the user that reminders are armed, which is exactly what the app claims to do.

**Degradation:** if `SCHEDULE_EXACT_ALARM` is not granted, notify-kit falls back to `setAndAllowWhileIdle` (inexact) rather than crashing or dropping the trigger. Timing may drift by minutes; delivery survives. The app's permission-state UI surfaces this as `partial`.

### Permission model

Android permissions declared by `apps/mobile/plugins/withAndroidNotificationPermissions.js`:

- `POST_NOTIFICATIONS` (Android 13+ runtime permission).
- `SCHEDULE_EXACT_ALARM` (user-granted special app access, follows the documented Android 14+ lifecycle: declare → `canScheduleExactAlarms()` → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` → recheck → reschedule → degrade if denied).
- `RECEIVE_BOOT_COMPLETED` (notify-kit re-registers pending alarms after reboot).
- `WAKE_LOCK` (deliver notifications while the device wakes from idle).

**Deliberately NOT declared: `USE_EXACT_ALARM`.** Google Play restricts this to apps whose core function is an alarm clock or calendar; medication reminders are not a verifiably approved category, and declaring it is a Play-review liability. `SET_ALARM_CLOCK` works with `SCHEDULE_EXACT_ALARM` alone. The plugin actively strips `USE_EXACT_ALARM` from the manifest if a stale build leaves it behind.

The `checkExactAlarmPermission()` function reads the real notify-kit `getNotificationSettings().android.alarm` value — no more hardcoded `Promise.resolve(true)`. The pure derivation lives in `@taskrx/shared/notifications/permission-state.ts` with 11 vitest cases covering NOT_SUPPORTED / DISABLED / ENABLED × NOT_DETERMINED / DENIED / AUTHORIZED / PROVISIONAL.

## Testing

- `packages/shared` — 348 vitest cases, the only workspace with a coverage gate (100% across statements/branches/functions/lines). The gate's `include[]` now also lists `src/sync/conflict.ts`, `src/timeline/projection.ts`, `src/sweep/planner.ts`, `src/backlog/completion.ts` and `src/notifications/tier.ts`; `src/notifications/permission-state.ts` is still specced but unmeasured.
- `packages/convex` — 55 `convex-test` cases run against an in-memory deployment, including the schedule validator's five variants and the two malformed payloads it must reject.
- `apps/mobile` — 87 vitest cases: `SyncWorker.drain()` re-entrancy, `rescheduleAll`'s two-Tier cancel pass and which trigger each Kind is armed with, the pull merge, the harness actions, and the missed sweep's persistence pass (mint vs settle, and two overlapping sweeps).
- `apps/mobile/scripts/notification-harness.sh` — adb-driven bash script; **11 cases**, of which `--all` runs the nine local-only ones (killed-app, `SET_ALARM_CLOCK` primitive in use, Doze, exact-alarm-denied fallback, boot reschedule, 30-med stress, weekly schedule, task-Tier delivery, task flood vs dose) and `--auth` the two signed-in ones. Emits JSON to `.omo/evidence/`. All nine local-only cases pass on a Pixel 8 Pro / Android 17 as of 2026-08-07; see `docs/poc-results.md`, including the two rig preconditions (the app must be signed out, and cases 3 and 5 need separate invocations because each leaves the keyguard up).
- Hidden `/harness` deep-link route in the app (`apps/mobile/app/harness.tsx`) is invoked by the harness via `adb shell am start`. Security carve-out in `AuthRedirectGate` bypasses the sign-in redirect for that route only — notifications are local, so the harness must work on a fresh install.

## Security

- Clerk issues JWTs after magic-link sign-in.
- Convex verifies the JWT and enforces `userId` on all reads/writes.
- No secrets are committed; environment variables live in `.env` (ignored by git).
- The `/harness` route bypasses auth by design — it is not linked from any UI and its actions are local-only (notify-kit scheduling), so this does not widen the app's attack surface. Not present in production builds by default (`__DEV__` guard is implicit through expo-router file-based routing plus the dev-only harness intent).

## Deferred to v2

- iOS support (the shared engine + conflict + horizon code is iOS-ready; only the Android-specific notification layer needs an iOS twin).
- Web app (Expo web can serve UI, but `react-native-notify-kit` does not run on web — web would be a management surface, not a reminder surface).
- Drug interaction checking.
- Task manager features.
- Push notifications (currently local-only; caregiver alerts via FCM deferred).
- OTA updates (disabled in this POC).
