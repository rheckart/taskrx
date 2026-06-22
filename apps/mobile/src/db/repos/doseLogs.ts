import type { DoseLog, DoseStatus } from "@taskrx/shared";

import { getDb } from "../index";

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
 */

interface DoseLogRow {
  readonly id: string;
  readonly user_id: string;
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
    id,
    userId: input.userId,
    medicationId: input.medicationId,
    scheduledTime: input.scheduledTime,
    status: "pending",
    takenAt: null,
    notes: null,
    createdAt: now,
    updatedAt: now,
  });

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
    id,
    userId: updated.userId,
    medicationId: updated.medicationId,
    scheduledTime: updated.scheduledTime,
    status,
    takenAt: takenAt ?? null,
    notes: notes ?? null,
    createdAt: updated.createdAt,
    updatedAt: now,
  });

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
