# TaskRx Architecture

This document describes the architecture of the TaskRx proof-of-concept as implemented through Day 7. It combines the design from the POC plan §5 and §6 with the actual sync implementation.

## High-level diagram

```
┌────────────────────────────────────┐         ┌──────────────────────────────┐
│  Expo App (Android)                 │         │ Convex Backend               │
│                                    │         │                              │
│  ┌──────────────────────────────┐  │         │ Schema:                      │
│  │ expo-sqlite (source of      │  │         │  - medications               │
│  │  truth on device)            │  │         │  - doseLogs                  │
│  │  - medications               │  │         │                              │
│  │  - dose_logs                 │  │         │ Functions:                   │
│  │  - sync_queue                │  │         │  - medications.{query,...}   │
│  └──────────────────────────────┘  │         │  - doseLogs.{query,...}      │
│           │                         │         │  - rxnorm.search (Action)    │
│           ▼                         │         │                              │
│  ┌──────────────────────────────┐  │         │ Clerk JWT verifier:          │
│  │ scheduleEngine.ts            │  │         │  - enforces userId on all    │
│  │  - computes next N doses     │  │         │    reads/writes              │
│  │  - rolling scheduler        │  │         │                              │
│  └──────────────────────────────┘  │         └──────────────┬───────────────┘
│           │                         │                        │
│           ▼                         │                        │
│  ┌──────────────────────────────┐  │                        │
│  │ Notifee (local notifications)│  │                        │
│  │  - reads next 64 doses       │  │                        │
│  │  - schedules via OS          │  │                        │
│  └──────────────────────────────┘  │                        │
│           │                         │                        │
│           ▼                         │                        │
│  ┌──────────────────────────────┐  │  HTTPS (Convex client)  │
│  │ syncWorker.ts                │──┼────────────────────────▶
│  │  - drains sync_queue         │  │                        │
│  │  - retries with backoff      │  │◀─────── realtime ──────│
│  │  - listens for connectivity  │  │                        │
│  └──────────────────────────────┘  │                        │
│                                    │                        │
│  Clerk (auth) ─────────────────────┼────────────────────────▶
│  (JWT attached to Convex requests) │                        │
└────────────────────────────────────┘
```

## Data-flow rules

1. **Local SQLite is the source of truth on device.** All reads/writes go through repos that hit SQLite first.
2. **Convex is the source of truth in the cloud.** Sync pushes local changes up and pulls remote changes in.
3. **The notification scheduler reads from SQLite, never from Convex.** This is why reminders fire offline.
4. **Schedule computation is pure + deterministic.** Given a med config + current time, it returns the next N dose times. No DB calls inside the engine.
5. **Sync queue is append-only.** Failed syncs stay in the queue with an attempt count; they don't get lost.

## Local SQLite schema

See `apps/mobile/src/db/migrations/001_init.sql` and `002_add_convex_id.sql`.

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

## Convex schema

See `packages/convex/convex/schema.ts`.

- `medications` — mirrors local medication rows, keyed by `userId`.
- `doseLogs` — mirrors local dose logs, with `medicationId` as a reference to the Convex `medications` table.
- Soft deletes use `deletedAt` on Convex; local rows are hard-deleted or marked inactive depending on the flow.

## Sync implementation

### Push (`apps/mobile/src/sync/worker.ts`)

The sync worker drains `sync_queue`:

1. Triggered on app foreground and on `NetInfo` reconnect.
2. For each queue entry, calls the matching Convex mutation with the payload.
3. On success: deletes the queue entry and marks the source row `sync_status = 'synced'`.
4. On error: increments `attempts`, applies exponential backoff (max 5 attempts), then marks `sync_status = 'error'` and keeps the entry in the queue.
5. Heartbeat: pings Convex every 30s when foregrounded to fast-path the queue.

### Pull (`apps/mobile/src/sync/pull.ts`)

Realtime hooks subscribe to Convex queries and mirror changes into SQLite:

- `useMedicationsSync(userId)` — subscribes to `convex.medications.list`, upserts by local `id`, last-write-wins by `updatedAt`.
- `useDoseLogsSync(userId)` — subscribes to `convex.doseLogs.list`, same merge strategy.
- `useConvexSyncStatus()` — returns `'synced' | 'pending' | 'offline'` for the UI badge.

### Conflict resolution (`apps/mobile/src/sync/conflict.ts`)

- Last-write-wins by `updated_at`.
- Dose logs are append-only history; a `taken` status is never overwritten remotely.
- Medication conflicts keep the latest `updated_at` and log a warning.

## Notification flow

1. User saves a medication → repo writes to SQLite and enqueues a sync entry.
2. `rescheduleAll(userId)` reads all active meds from SQLite.
3. `scheduleEngine.ts` computes the next N doses for each med.
4. Notifee schedules timestamp-triggered notifications via `AlarmManager.setExactAndAllowWhileIdle`.
5. When a notification fires and the user taps it, the tap handler navigates to `/dose/log` with `medicationId` and `scheduledTime`.
6. Dose logging writes to SQLite and enqueues sync.

## Security

- Clerk issues JWTs after magic-link sign-in.
- Convex verifies the JWT and enforces `userId` on all reads/writes.
- No secrets are committed; environment variables live in `.env` (ignored by git).

## Deferred to v2

- iOS support
- Web app
- Drug interaction checking
- Task manager features
- Push notifications (currently local-only)
- OTA updates (disabled in this POC)
