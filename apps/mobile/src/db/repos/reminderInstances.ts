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
 * Enqueue a sync entry for a `reminder_instances` row that was written
 * *outside* this repo.
 *
 * Migration 005 backfilled instances from `dose_logs`, and a `.sql` file
 * cannot call TypeScript, so not one of those rows ever produced a
 * `sync_queue` entry. Migration 007 then cleared the `convex_id` values 005
 * had copied across from `dose_logs` and returned the rows to `pending`. The
 * result was that an existing install's entire pre-cutover dose history stayed
 * on the device, with the Convex `reminderInstances` table beginning at the
 * cutover.
 *
 * An earlier version of this comment justified that as "server-side adherence
 * was computed over a history with its front half missing". No such
 * computation exists: `computeAdherence`
 * (`packages/shared/src/adherence/calculator.ts`) has no call site outside
 * `packages/shared/src`, and the Convex `reminderInstances` table has no
 * reader at all. What is actually lost is the history itself — it lives only
 * on one device, so a reinstall or a second device starts from the cutover.
 * That is reason enough for this function; the invented consumer was not.
 *
 * This is the instance-side counterpart to
 * `reminders.enqueueExternalWrite`, which covers the reminders half of the
 * same backfill.
 *
 * The payload is built here rather than at the call sites so there is exactly
 * one definition of an instance's sync shape. It matches what `create` and
 * `updateStatus` enqueue field for field, because that is what
 * `processReminderInstanceEntry` destructures — `reminderKind` included, even
 * though `api.reminderInstances.create` re-reads it off the parent document
 * rather than trusting the copy.
 *
 * The operation is chosen from `convex_id` rather than passed in: a row the
 * server has never seen needs `create`, and one it has needs `update`. Getting
 * this backwards is not a cosmetic error — the `update` branch resolves the
 * document id first, and `resolveReminderInstanceId` throws when there is no
 * document to resolve, so the entry burns all five attempts and dead-letters.
 * A replayed `create` is cheap by comparison: `api.reminderInstances.create`
 * patches on a `localId` hit instead of inserting a duplicate.
 *
 * There is no `delete` case, for the reason the header gives: an instance is
 * never deleted, so a removal already arrives as an `update` carrying the new
 * status.
 *
 * A no-op for an absent row: there is nothing to describe and no user to
 * attribute it to.
 */
export async function enqueueExternalWrite(id: string): Promise<void> {
  // One raw read of the whole row rather than `get` plus `getConvexId`:
  // `reconcilePending` runs this across the entire backlog, so a second round
  // trip per row is 2N. `reminders.ts` keeps its raw read alongside `get`
  // because two storage-only columns feed its payload; here `convex_id` is the
  // only column the domain shape drops, so a single `SELECT *` serves both the
  // operation choice and the payload.
  const db = await getDb();
  const row = await db.getFirstAsync<ReminderInstanceRow>(
    "SELECT * FROM reminder_instances WHERE id = ?",
    id
  );
  if (row === null) {
    return;
  }

  const existing = rowToInstance(row);
  await enqueue(
    "reminder_instance",
    id,
    row.convex_id === null ? "create" : "update",
    {
      localId: id,
      userId: existing.userId,
      reminderId: existing.reminderId,
      reminderKind: existing.reminderKind,
      scheduledTime: existing.scheduledTime,
      status: existing.status,
      takenAt: existing.takenAt ?? null,
      snoozedUntil: existing.snoozedUntil ?? null,
      snoozeCount: existing.snoozeCount,
      notes: existing.notes ?? null,
      createdAt: existing.createdAt,
      updatedAt: existing.updatedAt,
    }
  );
}

/**
 * Enqueue every instance that is `pending` but has nothing in the queue to
 * make it un-pend, and return how many were enqueued.
 *
 * The instance half of `reminders.reconcilePending`, closing the same two
 * gaps:
 *
 *   * Migration 005's backfill cannot enqueue and 007 re-pends what it wrote.
 *     Without this pass those rows never sync, and unlike a reminder there is
 *     no recovery by accident: an instance is append-only history, so there is
 *     no "the user edits it again later" that would enqueue it after the fact.
 *   * An enqueue that failed after its row write — the best-effort behaviour
 *     the header describes — leaves a `pending` row with no queue entry.
 *
 * The `NOT EXISTS` is the whole safety argument: a row that already has a live
 * queue entry is skipped, so this cannot duplicate work, and it costs nothing
 * once the backlog is clear. That rests entirely on `entity_type` matching
 * what `create` and `updateStatus` actually write — `'reminder_instance'`, the
 * spelling the `sync_queue` CHECK admits. A value the queue never holds would
 * make the subquery vacuously false, and this would re-enqueue every pending
 * instance on every drain without bound. Migration 008 indexes both sides
 * (`idx_reminder_instances_sync_status`, and `idx_sync_queue_entity` on
 * `(entity_type, entity_id, dead_letter)`), so the subquery is an index-only
 * seek instead of a scan of the queue per candidate row.
 *
 * `dead_letter = 0` is narrow but real, exactly as in `reminders.ts`. In the
 * ordinary case it does nothing: dead-lettering runs `markSourceError` first,
 * which sets the instance to `'error'`, and the `sync_status = 'pending'`
 * filter then excludes it. But `markSourceError` swallows its own failures
 * while `markDeadLettered` still runs, so a `pending` instance whose only entry
 * is dead-lettered is reachable — and without this clause it would be skipped
 * forever, because that entry is one nothing will ever drain.
 *
 * Widening the status filter to admit `'error'` would be worse than leaving it
 * — a dead-lettered entry has `dead_letter = 1`, so `NOT EXISTS` would hold and
 * the same doomed write would be re-enqueued forever.
 *
 * One state this cannot rescue: a row with a `convex_id` but a status still
 * `'pending'` yields an `update` entry, and `processReminderInstanceEntry`
 * refuses to sync a pending status, so it dead-letters. Reaching that needs a
 * `setConvexId` that landed while the `markSynced` right behind it did not, so
 * it is a symptom of a failing local database rather than of the backfill this
 * pass exists for.
 */
export async function reconcilePending(): Promise<number> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ readonly id: string }>(
    `SELECT i.id
     FROM reminder_instances i
     WHERE i.sync_status = 'pending'
       AND NOT EXISTS (
         SELECT 1 FROM sync_queue q
         WHERE q.entity_type = 'reminder_instance'
           AND q.entity_id = i.id
           AND q.dead_letter = 0
       )`
  );

  for (const row of rows) {
    await enqueueExternalWrite(row.id);
  }

  return rows.length;
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
