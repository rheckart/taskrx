import type {
  Priority,
  Reminder,
  ReminderKind,
  ScheduleTimes,
  Weekday,
} from "@taskrx/shared";
import { PRIORITIES, REMINDER_KINDS, dailyTimes } from "@taskrx/shared";

import { getDb } from "../index";

import { generateId } from "./ids";
import { enqueue } from "./syncQueue";

/**
 * Local SQLite repository for unified reminders.
 *
 * The row shape is snake_case (matching `004_unified_reminders.sql`); this
 * module returns the camelCase `Reminder` from `@taskrx/shared`. It is the
 * unified counterpart to `medications.ts`, which stays in place for the POC
 * tables until the cutover completes.
 *
 * Every write enqueues a `sync_queue` row with entity_type='reminder' so the
 * sync worker can push it to Convex, exactly as `medications.ts` does. This
 * was not possible until `006_widen_sync_queue.sql`: 001's `entity_type` CHECK
 * admitted only 'medication' and 'dose_log', SQLite cannot widen a CHECK
 * without rebuilding the table, and an enqueue would have thrown a constraint
 * failure at runtime. Until 006 landed, reminder writes were local-only and
 * never reached Convex.
 *
 * The enqueue is best-effort in the same sense as `medications.ts`: it is not
 * in the same transaction as the row write, so a failure to enqueue leaves the
 * row's `sync_status` at 'pending' for a later reconciliation pass rather than
 * rolling back a change the user already made.
 *
 * The `schedule`, `tags`, and `caregiver_ids` columns hold JSON. Reads parse
 * defensively — a column can only be as well-formed as whatever wrote it, and
 * a malformed blob must not take down the medication list.
 *
 * `rxcui` is storage-only. It is not on the domain `Reminder` (the unified
 * type does not model RxNorm) but the column exists locally and server-side so
 * the code survives the retirement of `medications`. It is therefore writable
 * through `create`/`update` and readable through `getRxcui`, the same shape of
 * accessor `convex_id` gets, rather than appearing on the returned object.
 */

interface ReminderRow {
  readonly id: string;
  readonly user_id: string;
  readonly convex_id: string | null;
  readonly kind: ReminderKind;
  readonly name: string;
  readonly rxcui: string | null;
  readonly dosage: string | null;
  readonly instructions: string | null;
  readonly schedule: string;
  readonly priority: string;
  readonly tags: string;
  readonly pills_remaining: number | null;
  readonly pills_per_dose: number | null;
  readonly refill_reminder_days: number | null;
  readonly caregiver_ids: string;
  readonly active: number;
  readonly created_at: number;
  readonly updated_at: number;
  readonly sync_status: "pending" | "synced" | "error";
}

export interface CreateReminderRowInput {
  readonly userId: string;
  readonly kind: ReminderKind;
  readonly name: string;
  readonly schedule: ScheduleTimes;
  readonly rxcui?: string;
  readonly dosage?: string;
  readonly instructions?: string;
  readonly priority?: Priority;
  readonly tags?: readonly string[];
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly caregiverIds?: readonly string[];
}

/**
 * Patch for `update`. A key present with an `undefined` value clears the
 * column; a key that is absent leaves the column alone. That distinction is
 * why the implementation tests `"key" in patch` rather than using `??`.
 */
export interface UpdateReminderPatch {
  readonly name?: string;
  readonly rxcui?: string | undefined;
  readonly dosage?: string | undefined;
  readonly instructions?: string | undefined;
  readonly schedule?: ScheduleTimes;
  readonly priority?: Priority;
  readonly tags?: readonly string[];
  readonly pillsRemaining?: number | undefined;
  readonly pillsPerDose?: number | undefined;
  readonly refillReminderDays?: number | undefined;
  readonly caregiverIds?: readonly string[];
  readonly active?: boolean;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseJson(json: string): unknown {
  try {
    return JSON.parse(json) as unknown;
  } catch {
    return null;
  }
}

function toStringArray(value: unknown): readonly string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((entry): entry is string => typeof entry === "string");
}

function toWeekdayArray(value: unknown): readonly Weekday[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const days: Weekday[] = [];
  for (const entry of value) {
    if (
      typeof entry === "number" &&
      Number.isInteger(entry) &&
      entry >= 0 &&
      entry <= 6
    ) {
      days.push(entry as Weekday);
    }
  }
  return days;
}

/**
 * Reads the `schedule` column back into the domain union.
 *
 * This reads the DOMAIN shape (lowercase `type` discriminator), not the
 * PascalCase `$type` wire shape that `@taskrx/shared`'s `parseScheduleTimes`
 * consumes — that one exists for the archived C# API and is not what migration
 * 005 writes.
 *
 * An unrecognized or malformed blob degrades to an empty daily schedule, which
 * produces no notifications, rather than throwing. Losing a schedule is
 * recoverable by editing the reminder; a crash on the list screen is not.
 */
function parseSchedule(json: string): ScheduleTimes {
  const parsed = parseJson(json);
  if (!isRecord(parsed)) {
    return dailyTimes([]);
  }

  switch (parsed.type) {
    case "daily":
      return { type: "daily", times: toStringArray(parsed.times) };
    case "interval": {
      const hours = typeof parsed.hours === "number" ? parsed.hours : 0;
      const startAt = typeof parsed.startAt === "number" ? parsed.startAt : 0;
      return { type: "interval", hours, startAt };
    }
    case "weekly":
      return {
        type: "weekly",
        daysOfWeek: toWeekdayArray(parsed.daysOfWeek),
        times: toStringArray(parsed.times),
      };
    default:
      return dailyTimes([]);
  }
}

function parsePriority(value: string): Priority {
  return (PRIORITIES as readonly string[]).includes(value)
    ? (value as Priority)
    : "normal";
}

function isReminderKind(value: string): value is ReminderKind {
  return (REMINDER_KINDS as readonly string[]).includes(value);
}

function rowToReminder(row: ReminderRow): Reminder {
  return {
    id: row.id,
    userId: row.user_id,
    kind: isReminderKind(row.kind) ? row.kind : "task",
    name: row.name,
    dosage: row.dosage ?? undefined,
    instructions: row.instructions ?? undefined,
    schedule: parseSchedule(row.schedule),
    priority: parsePriority(row.priority),
    tags: toStringArray(parseJson(row.tags)),
    pillsRemaining: row.pills_remaining ?? undefined,
    pillsPerDose: row.pills_per_dose ?? undefined,
    refillReminderDays: row.refill_reminder_days ?? undefined,
    caregiverIds: toStringArray(parseJson(row.caregiver_ids)),
    active: row.active === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

/**
 * Insert a new reminder. Returns the inserted row in domain form.
 * Enqueues a `create` operation to the sync queue.
 */
export async function create(input: CreateReminderRowInput): Promise<Reminder> {
  const db = await getDb();
  const now = Date.now();
  const id = generateId();

  await db.runAsync(
    `INSERT INTO reminders (
      id, user_id, kind, name, rxcui, dosage, instructions, schedule,
      priority, tags, pills_remaining, pills_per_dose, refill_reminder_days,
      caregiver_ids, active, created_at, updated_at, sync_status
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'pending')`,
    id,
    input.userId,
    input.kind,
    input.name,
    input.rxcui ?? null,
    input.dosage ?? null,
    input.instructions ?? null,
    JSON.stringify(input.schedule),
    input.priority ?? "normal",
    JSON.stringify(input.tags ?? []),
    input.pillsRemaining ?? null,
    input.pillsPerDose ?? null,
    input.refillReminderDays ?? null,
    JSON.stringify(input.caregiverIds ?? []),
    now,
    now
  );

  const inserted = await get(id);
  if (inserted === null) {
    throw new Error("Failed to read inserted reminder");
  }

  await enqueue("reminder", id, "create", {
    localId: id,
    userId: input.userId,
    kind: input.kind,
    name: input.name,
    rxcui: input.rxcui ?? null,
    dosage: input.dosage ?? null,
    instructions: input.instructions ?? null,
    schedule: input.schedule,
    priority: input.priority ?? "normal",
    tags: input.tags ?? [],
    pillsRemaining: input.pillsRemaining ?? null,
    pillsPerDose: input.pillsPerDose ?? null,
    refillReminderDays: input.refillReminderDays ?? null,
    caregiverIds: input.caregiverIds ?? [],
    active: true,
    createdAt: now,
    updatedAt: now,
  });

  return inserted;
}

/**
 * List a user's active reminders, newest first. Pass `kind` to restrict to
 * medications or tasks; omit it for the combined list, which is the whole
 * point of the unified table.
 */
export async function list(
  userId: string,
  kind?: ReminderKind
): Promise<readonly Reminder[]> {
  const db = await getDb();
  const rows =
    kind === undefined
      ? await db.getAllAsync<ReminderRow>(
          `SELECT * FROM reminders
           WHERE user_id = ? AND active = 1
           ORDER BY created_at DESC`,
          userId
        )
      : await db.getAllAsync<ReminderRow>(
          `SELECT * FROM reminders
           WHERE user_id = ? AND active = 1 AND kind = ?
           ORDER BY created_at DESC`,
          userId,
          kind
        );
  return rows.map(rowToReminder);
}

/**
 * Fetch a single reminder by id. Returns `null` if not found.
 */
export async function get(id: string): Promise<Reminder | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<ReminderRow>(
    "SELECT * FROM reminders WHERE id = ?",
    id
  );
  return row === null ? null : rowToReminder(row);
}

/**
 * Apply a patch to a reminder. Bumps `updated_at` and returns it to
 * `sync_status = 'pending'`. Enqueues an `update` operation to the sync queue.
 */
export async function update(
  id: string,
  patch: UpdateReminderPatch
): Promise<Reminder> {
  const db = await getDb();
  const existing = await get(id);
  if (existing === null) {
    throw new Error(`Reminder ${id} not found`);
  }

  const now = Date.now();
  // `rxcui` is not on `Reminder`, so it is resolved against the column rather
  // than against `existing`.
  const nextRxcui = "rxcui" in patch ? patch.rxcui : await getRxcui(id);
  const next: Reminder = {
    ...existing,
    name: patch.name ?? existing.name,
    dosage: "dosage" in patch ? patch.dosage : existing.dosage,
    instructions:
      "instructions" in patch ? patch.instructions : existing.instructions,
    schedule: patch.schedule ?? existing.schedule,
    priority: patch.priority ?? existing.priority,
    tags: patch.tags ?? existing.tags,
    pillsRemaining:
      "pillsRemaining" in patch
        ? patch.pillsRemaining
        : existing.pillsRemaining,
    pillsPerDose:
      "pillsPerDose" in patch ? patch.pillsPerDose : existing.pillsPerDose,
    refillReminderDays:
      "refillReminderDays" in patch
        ? patch.refillReminderDays
        : existing.refillReminderDays,
    caregiverIds: patch.caregiverIds ?? existing.caregiverIds,
    active: patch.active ?? existing.active,
    updatedAt: now,
  };

  await db.runAsync(
    `UPDATE reminders
     SET name = ?, rxcui = ?, dosage = ?, instructions = ?, schedule = ?,
         priority = ?, tags = ?, pills_remaining = ?, pills_per_dose = ?,
         refill_reminder_days = ?, caregiver_ids = ?, active = ?,
         updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    next.name,
    nextRxcui ?? null,
    next.dosage ?? null,
    next.instructions ?? null,
    JSON.stringify(next.schedule),
    next.priority,
    JSON.stringify(next.tags),
    next.pillsRemaining ?? null,
    next.pillsPerDose ?? null,
    next.refillReminderDays ?? null,
    JSON.stringify(next.caregiverIds),
    next.active ? 1 : 0,
    now,
    id
  );

  const updated = await get(id);
  if (updated === null) {
    throw new Error(`Reminder ${id} disappeared after update`);
  }

  await enqueue("reminder", id, "update", {
    localId: id,
    userId: updated.userId,
    kind: updated.kind,
    name: updated.name,
    rxcui: nextRxcui ?? null,
    dosage: updated.dosage ?? null,
    instructions: updated.instructions ?? null,
    schedule: updated.schedule,
    priority: updated.priority,
    tags: updated.tags,
    pillsRemaining: updated.pillsRemaining ?? null,
    pillsPerDose: updated.pillsPerDose ?? null,
    refillReminderDays: updated.refillReminderDays ?? null,
    caregiverIds: updated.caregiverIds,
    active: updated.active,
    createdAt: updated.createdAt,
    updatedAt: now,
  });

  return updated;
}

/**
 * Soft-delete a reminder: sets `active = 0` and bumps `updated_at`.
 * Enqueues a `delete` operation to the sync queue.
 *
 * Never a row delete. The FK from `reminder_instances` cascades, so a hard
 * delete would take the instance history — including taken doses — with it.
 */
export async function softDelete(id: string): Promise<void> {
  const db = await getDb();
  // Read first, as `medications.softDelete` does: a delete for a row that is
  // not here is not a change to propagate, and the enqueued payload needs the
  // owning user.
  const existing = await get(id);
  if (existing === null) {
    return;
  }

  const now = Date.now();
  await db.runAsync(
    `UPDATE reminders
     SET active = 0, updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    now,
    id
  );

  await enqueue("reminder", id, "delete", {
    localId: id,
    userId: existing.userId,
    deletedAt: now,
  });
}

/**
 * Mark a reminder's sync status as `synced`.
 */
export async function markSynced(id: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminders SET sync_status = 'synced' WHERE id = ?",
    id
  );
}

/**
 * Mark a reminder's sync status as `error` and log the last error.
 */
export async function markError(id: string, error: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminders SET sync_status = 'error' WHERE id = ?",
    id
  );
  console.error(`[reminders] sync error for ${id}: ${error}`);
}

/**
 * Read the storage-only `rxcui` column for a reminder. Returns `null` when the
 * reminder is a task, or a medication that was never matched to RxNorm.
 */
export async function getRxcui(id: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ rxcui: string | null }>(
    "SELECT rxcui FROM reminders WHERE id = ?",
    id
  );
  return row?.rxcui ?? null;
}

/**
 * Store the Convex-generated id for a local reminder row.
 */
export async function setConvexId(
  localId: string,
  convexId: string
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE reminders SET convex_id = ? WHERE id = ?",
    convexId,
    localId
  );
}

/**
 * Look up the Convex id for a local reminder row.
 */
export async function getConvexId(localId: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ convex_id: string | null }>(
    "SELECT convex_id FROM reminders WHERE id = ?",
    localId
  );
  return row?.convex_id ?? null;
}
