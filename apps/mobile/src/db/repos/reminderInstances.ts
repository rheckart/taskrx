import type {
  InstanceStatus,
  ReminderInstance,
  ReminderKind,
} from "@taskrx/shared";
import { INSTANCE_STATUSES } from "@taskrx/shared";

import { getDb } from "../index";

import { generateId } from "./ids";
import { enqueue } from "./syncQueue";

/**
 * Local SQLite repository for reminder instances — one materialized
 * occurrence of a reminder, and the row that records what the user did about
 * it. The unified counterpart to `doseLogs.ts`.
 *
 * Instances are append-only history. Nothing in this module deletes a row:
 * a dropped instance is a dropped medication record, so "delete" is expressed
 * as a status change and the row stays readable forever.
 *
 * As in `reminders.ts`, every write enqueues a `sync_queue` row — here with
 * entity_type='reminder_instance'. That value only became insertable with
 * `006_widen_sync_queue.sql`, which rebuilt `sync_queue` to widen the
 * `entity_type` CHECK that 001 had pinned to 'medication' and 'dose_log'.
 * Before it, an enqueue here raised a constraint failure, so instance writes
 * were local-only and never reached Convex.
 *
 * There is no `delete` enqueue because there is no delete: a "deletion" is a
 * status change, and the resulting `update` entry carries it.
 */

interface ReminderInstanceRow {
  readonly id: string;
  readonly reminder_id: string;
  readonly user_id: string;
  readonly convex_id: string | null;
  readonly reminder_kind: ReminderKind;
  readonly scheduled_time: number;
  readonly status: string;
  readonly taken_at: number | null;
  readonly snoozed_until: number | null;
  readonly snooze_count: number;
  readonly notes: string | null;
  readonly created_at: number;
  readonly updated_at: number;
  readonly sync_status: "pending" | "synced" | "error";
}

export interface CreateReminderInstanceRowInput {
  readonly userId: string;
  readonly reminderId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
}

export interface ListInstancesFilter {
  readonly userId: string;
  readonly reminderId?: string;
  readonly reminderKind?: ReminderKind;
  readonly since?: number;
  readonly until?: number;
  readonly status?: InstanceStatus;
}

function parseStatus(value: string): InstanceStatus {
  return (INSTANCE_STATUSES as readonly string[]).includes(value)
    ? (value as InstanceStatus)
    : "pending";
}

function rowToInstance(row: ReminderInstanceRow): ReminderInstance {
  return {
    id: row.id,
    reminderId: row.reminder_id,
    userId: row.user_id,
    reminderKind: row.reminder_kind,
    scheduledTime: row.scheduled_time,
    status: parseStatus(row.status),
    takenAt: row.taken_at ?? undefined,
    snoozedUntil: row.snoozed_until ?? undefined,
    snoozeCount: row.snooze_count,
    notes: row.notes ?? undefined,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

/**
 * Materialize a new pending instance for a scheduled occurrence.
 * Enqueues a `create` operation to the sync queue.
 */
export async function create(
  input: CreateReminderInstanceRowInput
): Promise<ReminderInstance> {
  const db = await getDb();
  const now = Date.now();
  const id = generateId();

  await db.runAsync(
    `INSERT INTO reminder_instances (
      id, reminder_id, user_id, reminder_kind, scheduled_time,
      status, taken_at, snoozed_until, snooze_count, notes,
      created_at, updated_at, sync_status
    ) VALUES (?, ?, ?, ?, ?, 'pending', NULL, NULL, 0, NULL, ?, ?, 'pending')`,
    id,
    input.reminderId,
    input.userId,
    input.reminderKind,
    input.scheduledTime,
    now,
    now
  );

  const inserted = await get(id);
  if (inserted === null) {
    throw new Error("Failed to read inserted reminder instance");
  }

  await enqueue("reminder_instance", id, "create", {
    localId: id,
    userId: input.userId,
    reminderId: input.reminderId,
    reminderKind: input.reminderKind,
    scheduledTime: input.scheduledTime,
    status: "pending",
    takenAt: null,
    snoozedUntil: null,
    snoozeCount: 0,
    notes: null,
    createdAt: now,
    updatedAt: now,
  });

  return inserted;
}

/**
 * List instances for a user, newest scheduled time first.
 */
export async function list(
  filter: ListInstancesFilter
): Promise<readonly ReminderInstance[]> {
  const db = await getDb();
  const conditions: string[] = ["user_id = ?"];
  const args: (string | number)[] = [filter.userId];

  if (filter.reminderId !== undefined) {
    conditions.push("reminder_id = ?");
    args.push(filter.reminderId);
  }
  if (filter.reminderKind !== undefined) {
    conditions.push("reminder_kind = ?");
    args.push(filter.reminderKind);
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
  const rows = await db.getAllAsync<ReminderInstanceRow>(
    `SELECT * FROM reminder_instances
     WHERE ${where}
     ORDER BY scheduled_time DESC`,
    ...args
  );
  return rows.map(rowToInstance);
}

/**
 * Fetch a single instance by id. Returns `null` if not found.
 */
export async function get(id: string): Promise<ReminderInstance | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<ReminderInstanceRow>(
    "SELECT * FROM reminder_instances WHERE id = ?",
    id
  );
  return row === null ? null : rowToInstance(row);
}

/**
 * Pending instances at or before `through`, oldest first — the query behind
 * the missed-instance sweep and the "what is due" screen. Served by the
 * partial index `idx_reminder_instances_pending`.
 */
export async function pendingThrough(
  userId: string,
  through: number
): Promise<readonly ReminderInstance[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<ReminderInstanceRow>(
    `SELECT * FROM reminder_instances
     WHERE user_id = ? AND status = 'pending' AND scheduled_time <= ?
     ORDER BY scheduled_time ASC`,
    userId,
    through
  );
  return rows.map(rowToInstance);
}

async function updateStatus(
  id: string,
  status: InstanceStatus,
  fields: {
    readonly takenAt?: number;
    readonly snoozedUntil?: number;
    readonly notes?: string;
    readonly incrementSnooze?: boolean;
  }
): Promise<ReminderInstance> {
  const db = await getDb();
  const existing = await get(id);
  if (existing === null) {
    throw new Error(`Reminder instance ${id} not found`);
  }

  const now = Date.now();
  await db.runAsync(
    `UPDATE reminder_instances
     SET status = ?, taken_at = ?, snoozed_until = ?,
         snooze_count = snooze_count + ?, notes = ?,
         updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    status,
    fields.takenAt ?? null,
    fields.snoozedUntil ?? null,
    fields.incrementSnooze === true ? 1 : 0,
    fields.notes ?? existing.notes ?? null,
    now,
    id
  );

  const updated = await get(id);
  if (updated === null) {
    throw new Error(`Reminder instance ${id} disappeared after update`);
  }

  // Read off `updated` rather than off `fields`: `snooze_count` is an in-SQL
  // increment and `notes` falls back to the existing value, so the row is the
  // only place the post-write truth exists.
  await enqueue("reminder_instance", id, "update", {
    localId: id,
    userId: updated.userId,
    reminderId: updated.reminderId,
    reminderKind: updated.reminderKind,
    scheduledTime: updated.scheduledTime,
    status: updated.status,
    takenAt: updated.takenAt ?? null,
    snoozedUntil: updated.snoozedUntil ?? null,
    snoozeCount: updated.snoozeCount,
    notes: updated.notes ?? null,
    createdAt: updated.createdAt,
    updatedAt: now,
  });

  return updated;
}

/**
 * Mark an instance taken at `takenAt`, with optional notes.
 */
export async function markTaken(
  id: string,
  takenAt: number,
  notes?: string
): Promise<ReminderInstance> {
  return updateStatus(id, "taken", {
    takenAt,
    ...(notes === undefined ? {} : { notes }),
  });
}

/**
 * Snooze an instance until `snoozedUntil` and increment `snooze_count`.
 *
 * The status is 'snoozed', not 'skipped' or 'missed': the adherence
 * calculator counts taken/missed/skipped and a snooze is none of those, so a
 * snoozed dose does not break a streak.
 */
export async function markSnoozed(
  id: string,
  snoozedUntil: number
): Promise<ReminderInstance> {
  return updateStatus(id, "snoozed", {
    snoozedUntil,
    incrementSnooze: true,
  });
}

/**
 * Mark an instance skipped — a deliberate user decision, with optional notes.
 */
export async function markSkipped(
  id: string,
  notes?: string
): Promise<ReminderInstance> {
  return updateStatus(id, "skipped", notes === undefined ? {} : { notes });
}

/**
 * Mark an instance missed — the sweep's verdict, not a user action.
 */
export async function markMissed(id: string): Promise<ReminderInstance> {
  return updateStatus(id, "missed", {});
}

/**
 * Find the pending instance for an exact reminder + scheduled time, if one
 * has already been materialized. Keeps a notification tap from creating a
 * second row for the same occurrence.
 */
export async function findPendingForOccurrence(
  userId: string,
  reminderId: string,
  scheduledTime: number
): Promise<ReminderInstance | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<ReminderInstanceRow>(
    `SELECT * FROM reminder_instances
     WHERE user_id = ? AND reminder_id = ? AND scheduled_time = ?
       AND status = 'pending'
     ORDER BY created_at DESC
     LIMIT 1`,
    userId,
    reminderId,
    scheduledTime
  );
  return row === null ? null : rowToInstance(row);
}

/**
 * The most recent taken instance for a reminder, or `null` if it has never
 * been marked taken.
 */
export async function lastTakenFor(
  userId: string,
  reminderId: string
): Promise<ReminderInstance | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<ReminderInstanceRow>(
    `SELECT * FROM reminder_instances
     WHERE user_id = ? AND reminder_id = ? AND status = 'taken'
     ORDER BY taken_at DESC, scheduled_time DESC
     LIMIT 1`,
    userId,
    reminderId
  );
  return row === null ? null : rowToInstance(row);
}

/**
 * Mark an instance's sync status as `synced`.
 */
export async function markSynced(id: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminder_instances SET sync_status = 'synced' WHERE id = ?",
    id
  );
}

/**
 * Mark an instance's sync status as `error` and log the last error.
 */
export async function markError(id: string, error: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminder_instances SET sync_status = 'error' WHERE id = ?",
    id
  );
  console.error(`[reminderInstances] sync error for ${id}: ${error}`);
}

/**
 * Store the Convex-generated id for a local instance row.
 */
export async function setConvexId(
  localId: string,
  convexId: string
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminder_instances SET convex_id = ? WHERE id = ?",
    convexId,
    localId
  );
}

/**
 * Look up the Convex id for a local instance row.
 */
export async function getConvexId(localId: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ convex_id: string | null }>(
    "SELECT convex_id FROM reminder_instances WHERE id = ?",
    localId
  );
  return row?.convex_id ?? null;
}
