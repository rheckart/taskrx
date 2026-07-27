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
│  │  - selectUpcomingDoses       │  │                        │
│  │    (global 64 horizon)       │  │                        │
│  │  - conflict resolution       │  │                        │
│  │  - permission-state derive   │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                        │                        │
│           ▼                        │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ react-native-notify-kit      │  │                        │
│  │  - SET_ALARM_CLOCK triggers  │  │                        │
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
5. **Notification horizon is global, not per-medication.** `selectUpcomingDoses` picks the 64 soonest doses across ALL active meds — iOS-cap-safe and, when both A@21:00 and B@10min-from-now exist, B always wins over A regardless of what A already had scheduled.
6. **Sync queue drains until MAX_ATTEMPTS (5) failures; then dead-letters.** Dead-lettered entries are retained (never deleted) for inspection and manual requeue.
7. **Conflict resolution rules live in one tested place.** `@taskrx/shared/sync/conflict` — local wins ties, and a locally-taken dose is never overwritten by a remote pull.

## Local SQLite schema

See `apps/mobile/src/db/migrations/001_init.sql`, `002_add_convex_id.sql`, and `003_sync_queue_dead_letter.sql`.

### `medications`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 generated on device |
| `user_id` | TEXT | Clerk user ID |
| `name` | TEXT | Display name |
| `rxcui` | TEXT | Optional RxNorm CUI |
| `dosage` | TEXT | Optional free text |
| `schedule_type` | TEXT | `daily` or `interval` |
| `schedule_times` | TEXT | JSON schedule payload |
| `active` | INTEGER | 1 = active |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge (migration 002) |

### `dose_logs`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID v4 generated on device |
| `user_id` | TEXT | Clerk user ID |
| `medication_id` | TEXT | FK to `medications(id)` |
| `scheduled_time` | INTEGER | Unix ms |
| `taken_at` | INTEGER | Unix ms, nullable |
| `status` | TEXT | `pending`, `taken`, `skipped`, `missed` |
| `notes` | TEXT | Optional |
| `created_at` | INTEGER | Unix ms |
| `updated_at` | INTEGER | Unix ms |
| `sync_status` | TEXT | `pending`, `synced`, or `error` |
| `convex_id` | TEXT | Local↔Convex ID bridge (migration 002) |

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

- `useMedicationsSync(userId)` — subscribes to `convex.medications.list`, upserts by local `id`.
- `useDoseLogsSync(userId)` — subscribes to `convex.doseLogs.list`, same merge strategy.
- `useConvexSyncStatus()` — returns `'synced' | 'pending' | 'offline'` for the UI badge.

### Conflict resolution (`packages/shared/src/sync/conflict.ts`)

Two pure functions consumed by both repo `upsertMany` paths:

- `shouldApplyRemoteMedication(local, remote)` — false when `local != null && local.updatedAt >= remote.updatedAt` (local wins ties). True otherwise.
- `shouldApplyRemoteDoseLog(local, remote)` — false when local is `taken` (never overwritten), or when the medication rule above would return false. True otherwise.

10 vitest cases cover the truth table incl. the safety-critical taken-dose preservation.

## Notification flow

### Scheduling

1. User saves a medication → repo writes to SQLite and enqueues a sync entry.
2. `rescheduleAll(userId)` reads all active meds from SQLite.
3. `selectUpcomingDoses(meds, Date.now(), 64)` from `@taskrx/shared` returns the 64 soonest doses **across all meds** (not per-med). This is iOS-cap-safe and ensures a med with a closer next dose always displaces one with a farther next dose regardless of how many alarms the farther med already has.
4. **Diff, don't rebuild:** read current pending trigger IDs from notify-kit, compare against the desired 64:
   - Cancel only the notifications that are no longer in the desired set.
   - Create only the notifications that are missing.
   - Deterministic IDs `${medicationId}-${scheduledTime}` make the diff safe.
   - Idempotent: two consecutive `rescheduleAll` calls with unchanged data cancel and create nothing.
5. Each new trigger is built with `AlarmType.SET_ALARM_CLOCK` — see the AlarmType decision below.
6. When a notification fires and the user taps it, the tap handler navigates to `/dose/log` with `medicationId` and `scheduledTime`.
7. Dose logging writes to SQLite and enqueues sync.

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

- `packages/shared` — 54 vitest cases (23 schedule engine + 10 global horizon + 10 conflict + 11 permission state).
- `apps/mobile/scripts/notification-harness.sh` — adb-driven bash script; 6 cases (killed-app, `SET_ALARM_CLOCK` primitive in use, Doze, exact-alarm-denied fallback, boot reschedule, 30-med stress). Emits JSON to `.omo/evidence/`.
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
