import type { DoseLog, DoseStatus } from "@taskrx/shared";
import { shouldApplyRemoteDoseLog } from "@taskrx/shared";

import { getDb } from "../index";

import { mirrorDoseLog, mirrorDoseLogRow } from "./dose-log-mirror";
import { enqueue } from "./syncQueue";

/**
 * Local SQLite repository for dose logs.
 *
 * The SQLite row shape uses snake_case (matching `001_init.sql`); the
 * repository returns camelCase objects matching `@taskrx/shared`'s
 * `DoseLog` type. The sync layer (Day 7) translates between this
 * camelCase shape and Convex's camelCase schema.
 *
 * Every write also enqueues a `sync_queue` entry so the sync worker can
 * push the change to Convex later. The enqueue is best-effort: a failure
 * to enqueue does not roll back the local write (the row's `sync_status`
 * stays `pending` and a future reconciliation pass can re-enqueue).
 *
 * Since the two cutovers, most of this repository is unreachable — but not all
 * of it, so be precise about which half.
 *
 * The write cutover took the content half: `dose/log.tsx` records a taking as a
 * `reminder_instances` row (`reminderInstances.ts`), so `create`, `markTaken`,
 * `markSkipped`, `markMissed`, `deleteDoseLog` and `findPendingForDose` went
 * quiet. The sync cutover took the pull half — `pull.ts` no longer calls
 * `useDoseLogsSync`, so `upsertMany` and `selectAll` are unreachable, and with
 * them `mirrorDoseLogRow`, whose only call site is inside `upsertMany` below.
 *
 * Still live: the sync-worker accessors — `setConvexId`, `getConvexId`,
 * `markSynced`, `markError`. `worker.ts` calls all four while draining a
 * `dose_log` queue entry, and an upgraded device can still hold pre-cutover
 * entries that nothing has drained yet. Nothing enqueues new ones, so the
 * backlog empties and never refills, but it is not empty by construction.
 *
 * What still touches the `dose_logs` TABLE, rather than coming through here, is
 * `dose-log-mirror.repairMissingProjections`, run by the sync drain to project
 * any pre-cutover row that never got an instance.
 *
 * Retained deliberately: neither cutover ticket deletes anything. The whole
 * file goes when `dose_logs` is retired.
 */

interface DoseLogRow {
  readonly id: string;
  readonly user_id: string;
  readonly convex_id: string | null;
  readonly medication_id: string;
  readonly scheduled_time: number;
  readonly taken_at: number | null;
  readonly status: DoseStatus;
  readonly notes: string | null;
  readonly created_at: number;
  readonly updated_at: number;
  readonly sync_status: "pending" | "synced" | "error";
}

export interface CreateDoseLogInput {
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
}

export interface ListDoseLogsFilter {
  readonly userId: string;
  readonly medicationId?: string;
  readonly since?: number;
  readonly until?: number;
  readonly status?: DoseStatus;
}

function rowToDoseLog(row: DoseLogRow): DoseLog {
  return {
    id: row.id,
    userId: row.user_id,
    medicationId: row.medication_id,
    scheduledTime: row.scheduled_time,
    takenAt: row.taken_at ?? undefined,
    status: row.status,
    notes: row.notes ?? undefined,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function generateId(): string {
  // Simple UUID v4 generator. We avoid `crypto.randomUUID()` because the
  // Hermes runtime in Expo SDK 56 does not expose it on the global scope
  // in all configurations; this implementation is dependency-free and
  // good enough for local-only IDs (collision risk is negligible for a
  // single-user device).
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i += 1) {
    bytes[i] = Math.floor(Math.random() * 256);
  }
  // Per RFC 4122 §4.4: set version (4) and variant (10xx) bits.
  const b6 = bytes[6] ?? 0;
  const b8 = bytes[8] ?? 0;
  bytes[6] = (b6 & 0x0f) | 0x40;
  bytes[8] = (b8 & 0x3f) | 0x80;

  const hex: string[] = [];
  for (let i = 0; i < 16; i += 1) {
    const byte = bytes[i] ?? 0;
    hex.push(byte.toString(16).padStart(2, "0"));
  }
  return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10, 16).join("")}`;
}

/**
 * Insert a new pending dose log. Returns the inserted row in camelCase form.
 * Enqueues a `create` operation to the sync queue.
 */
export async function create(input: CreateDoseLogInput): Promise<DoseLog> {
  const db = await getDb();
  const now = Date.now();
  const id = generateId();

  await db.runAsync(
    `INSERT INTO dose_logs (
      id, user_id, medication_id, scheduled_time,
      taken_at, status, notes, created_at, updated_at, sync_status
    ) VALUES (?, ?, ?, ?, NULL, 'pending', NULL, ?, ?, 'pending')`,
    id,
    input.userId,
    input.medicationId,
    input.scheduledTime,
    now,
    now
  );

  const inserted = await get(id);
  if (inserted === null) {
    throw new Error("Failed to read inserted dose log");
  }

  await enqueue("dose_log", id, "create", {
    localId: id,
    userId: input.userId,
    medicationId: input.medicationId,
    scheduledTime: input.scheduledTime,
    status: "pending",
    takenAt: null,
    notes: null,
    createdAt: now,
    updatedAt: now,
  });

  await mirrorDoseLog(id);

  return inserted;
}

/**
 * List dose logs for a user, newest first by scheduled_time DESC.
 *
 * Supports optional filters:
 *   - medicationId: only logs for that medication
 *   - since: scheduled_time >= since
 *   - until: scheduled_time <= until
 *   - status: exact status match
 */
export async function list(
  filter: ListDoseLogsFilter
): Promise<readonly DoseLog[]> {
  const db = await getDb();
  const conditions: string[] = ["user_id = ?"];
  const args: (string | number)[] = [filter.userId];

  if (filter.medicationId !== undefined) {
    conditions.push("medication_id = ?");
    args.push(filter.medicationId);
  }
  if (filter.since !== undefined) {
    conditions.push("scheduled_time >= ?");
    args.push(filter.since);
  }
  if (filter.until !== undefined) {
    conditions.push("scheduled_time <= ?");
    args.push(filter.until);
  }
  if (filter.status !== undefined) {
    conditions.push("status = ?");
    args.push(filter.status);
  }

  const where = conditions.join(" AND ");
  const rows = await db.getAllAsync<DoseLogRow>(
    `SELECT * FROM dose_logs WHERE ${where} ORDER BY scheduled_time DESC`,
    ...args
  );
  return rows.map(rowToDoseLog);
}

/**
 * Fetch a single dose log by id. Returns `null` if not found.
 */
export async function get(id: string): Promise<DoseLog | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<DoseLogRow>(
    "SELECT * FROM dose_logs WHERE id = ?",
    id
  );
  return row === null ? null : rowToDoseLog(row);
}

async function updateStatus(
  id: string,
  status: DoseStatus,
  takenAt: number | undefined,
  notes: string | undefined
): Promise<DoseLog> {
  const db = await getDb();
  const existing = await get(id);
  if (existing === null) {
    throw new Error(`Dose log ${id} not found`);
  }

  const now = Date.now();
  await db.runAsync(
    `UPDATE dose_logs
     SET status = ?, taken_at = ?, notes = ?, updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    status,
    takenAt ?? null,
    notes ?? null,
    now,
    id
  );

  const updated = await get(id);
  if (updated === null) {
    throw new Error(`Dose log ${id} disappeared after update`);
  }

  await enqueue("dose_log", id, "update", {
    localId: id,
    userId: updated.userId,
    medicationId: updated.medicationId,
    scheduledTime: updated.scheduledTime,
    status,
    takenAt: takenAt ?? null,
    notes: notes ?? null,
    createdAt: updated.createdAt,
    updatedAt: now,
  });

  // Covers `markTaken`, `markSkipped`, `markMissed` and `deleteDoseLog` — all
  // four route through here, so the projection follows every status change
  // without four separate mirror calls to keep in step.
  await mirrorDoseLog(id);

  return updated;
}

/**
 * Mark a dose as taken. Sets `taken_at`, `status = 'taken'`, bumps
 * `updated_at`, and enqueues a sync update.
 */
export async function markTaken(
  id: string,
  takenAt: number,
  notes?: string
): Promise<DoseLog> {
  return updateStatus(id, "taken", takenAt, notes);
}

/**
 * Mark a dose as skipped. Sets `status = 'skipped'`, optional notes,
 * bumps `updated_at`, and enqueues a sync update.
 */
export async function markSkipped(
  id: string,
  notes?: string
): Promise<DoseLog> {
  return updateStatus(id, "skipped", undefined, notes);
}

/**
 * Mark a dose as missed. Sets `status = 'missed'`, bumps `updated_at`,
 * and enqueues a sync update.
 */
export async function markMissed(id: string): Promise<DoseLog> {
  return updateStatus(id, "missed", undefined, undefined);
}

/**
 * Soft-delete a dose log by marking it `missed` and bumping `updated_at`.
 *
 * POC scope does not include actual row deletion; dose logs are treated
 * as append-only history. The sync worker will push the `missed` status
 * to Convex on Day 7.
 */
export async function deleteDoseLog(id: string): Promise<DoseLog> {
  return updateStatus(id, "missed", undefined, undefined);
}

/**
 * Find an existing pending dose log for a specific medication and scheduled
 * time. Returns `null` if none exists. Used by the dose logging screen to
 * avoid creating duplicate log rows when a notification is tapped.
 */
export async function findPendingForDose(
  userId: string,
  medicationId: string,
  scheduledTime: number
): Promise<DoseLog | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<DoseLogRow>(
    `SELECT * FROM dose_logs
     WHERE user_id = ? AND medication_id = ? AND scheduled_time = ? AND status = 'pending'
     ORDER BY created_at DESC
     LIMIT 1`,
    userId,
    medicationId,
    scheduledTime
  );
  return row === null ? null : rowToDoseLog(row);
}

/**
 * Return the most recent `taken` dose log for a medication, or `null` if
 * the medication has never been marked taken.
 */
export async function lastTakenFor(
  userId: string,
  medicationId: string
): Promise<DoseLog | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<DoseLogRow>(
    `SELECT * FROM dose_logs
     WHERE user_id = ? AND medication_id = ? AND status = 'taken'
     ORDER BY taken_at DESC, scheduled_time DESC
     LIMIT 1`,
    userId,
    medicationId
  );
  return row === null ? null : rowToDoseLog(row);
}

/**
 * Mark a dose log's sync status as `synced`.
 */
export async function markSynced(id: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE dose_logs SET sync_status = 'synced' WHERE id = ?",
    id
  );
}

/**
 * Mark a dose log's sync status as `error` and log the last error.
 */
export async function markError(id: string, error: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE dose_logs SET sync_status = 'error' WHERE id = ?",
    id
  );
  console.error(`[doseLogs] sync error for ${id}: ${error}`);
}

/**
 * Store the Convex-generated id for a local dose log row.
 */
export async function setConvexId(
  localId: string,
  convexId: string
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE dose_logs SET convex_id = ? WHERE id = ?",
    convexId,
    localId
  );
}

/**
 * Look up the Convex id for a local dose log row.
 */
export async function getConvexId(localId: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ convex_id: string | null }>(
    "SELECT convex_id FROM dose_logs WHERE id = ?",
    localId
  );
  return row?.convex_id ?? null;
}

/**
 * Read all dose logs for a user directly from SQLite (offline-first),
 * with optional filters.
 */
export async function selectAll(
  filter: ListDoseLogsFilter
): Promise<readonly DoseLog[]> {
  return list(filter);
}

/**
 * Upsert many dose logs from a remote pull. Skips rows where the remote
 * `updatedAt` is not newer than the local `updated_at` (last-write-wins).
 * Dose logs that are already `taken` locally are never overwritten remotely.
 */
export async function upsertMany(rows: readonly DoseLog[]): Promise<number> {
  const db = await getDb();
  let updated = 0;

  for (const remote of rows) {
    const local = await get(remote.id);
    if (!shouldApplyRemoteDoseLog(local, remote)) {
      continue;
    }

    await db.runAsync(
      `INSERT INTO dose_logs (
        id, user_id, medication_id, scheduled_time,
        taken_at, status, notes, created_at, updated_at, sync_status
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'synced')
      ON CONFLICT(id) DO UPDATE SET
        user_id = excluded.user_id,
        medication_id = excluded.medication_id,
        scheduled_time = excluded.scheduled_time,
        taken_at = excluded.taken_at,
        status = excluded.status,
        notes = excluded.notes,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at,
        sync_status = 'synced'`,
      remote.id,
      remote.userId,
      remote.medicationId,
      remote.scheduledTime,
      remote.takenAt ?? null,
      remote.status,
      remote.notes ?? null,
      remote.createdAt,
      remote.updatedAt
    );
    // Refreshes the projection without re-pending it. See `mirrorDoseLogRow`.
    //
    // Caught per row rather than allowed to escape. The dose log itself is
    // already committed by the write above, so letting a mirror failure abort
    // the loop would strand every later row in the batch with no projection
    // either — one failure becoming many. `repairMissingProjections` picks up
    // whatever is caught here on the next drain, which is the only reason
    // swallowing it is safe.
    try {
      await mirrorDoseLogRow(remote.id);
    } catch (error: unknown) {
      console.error(
        `[doseLogs] failed to mirror pulled dose log ${remote.id}:`,
        error
      );
    }
    updated += 1;
  }

  return updated;
}
