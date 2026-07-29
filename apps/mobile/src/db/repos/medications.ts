import type {
  DailySchedule,
  IntervalSchedule,
  Medication,
  ScheduleType,
} from "@taskrx/shared";
import { shouldApplyRemoteMedication } from "@taskrx/shared";

import { getDb } from "../index";

import {
  mirrorMedication,
  mirrorMedicationDelete,
  mirrorMedicationRow,
} from "./medication-mirror";
import { enqueue } from "./syncQueue";

/**
 * Local SQLite repository for medications.
 *
 * The SQLite row shape uses snake_case (matching `001_init.sql`); the
 * repository returns camelCase objects matching `@taskrx/shared`'s
 * `Medication` type. The sync layer (Day 7) translates between this
 * camelCase shape and Convex's camelCase schema.
 *
 * Every write also enqueues a `sync_queue` entry so the sync worker can
 * push the change to Convex later. The enqueue is best-effort: a failure
 * to enqueue does not roll back the local write (the row's `sync_status`
 * stays `pending` and a future reconciliation pass can re-enqueue).
 */

interface MedicationRow {
  readonly id: string;
  readonly user_id: string;
  readonly convex_id: string | null;
  readonly name: string;
  readonly rxcui: string | null;
  readonly dosage: string | null;
  readonly schedule_type: ScheduleType;
  readonly schedule_times: string;
  readonly active: number;
  readonly created_at: number;
  readonly updated_at: number;
  readonly sync_status: "pending" | "synced" | "error";
}

export interface CreateMedicationInput {
  readonly userId: string;
  readonly name: string;
  readonly rxcui?: string;
  readonly dosage?: string;
  readonly scheduleType: ScheduleType;
  readonly scheduleTimes: DailySchedule | IntervalSchedule;
}

export type UpdateMedicationPatch = Partial<
  Pick<
    CreateMedicationInput,
    "name" | "rxcui" | "dosage" | "scheduleType" | "scheduleTimes"
  >
> & { readonly active?: boolean };

function rowToMedication(row: MedicationRow): Medication {
  const scheduleTimes = parseScheduleTimes(
    row.schedule_type,
    row.schedule_times
  );
  return {
    id: row.id,
    userId: row.user_id,
    name: row.name,
    rxcui: row.rxcui ?? undefined,
    dosage: row.dosage ?? undefined,
    scheduleType: row.schedule_type,
    scheduleTimes,
    active: row.active === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    deletedAt: undefined,
  };
}

function parseScheduleTimes(
  type: ScheduleType,
  json: string
): DailySchedule | IntervalSchedule {
  const parsed: unknown = JSON.parse(json);
  if (type === "daily") {
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      Array.isArray((parsed as { times?: unknown }).times)
    ) {
      const times = (parsed as { times: readonly unknown[] }).times;
      const stringTimes = times.filter(
        (t): t is string => typeof t === "string"
      );
      return { times: stringTimes };
    }
    return { times: [] };
  }
  if (
    typeof parsed === "object" &&
    parsed !== null &&
    typeof (parsed as { hours?: unknown }).hours === "number" &&
    typeof (parsed as { startAt?: unknown }).startAt === "number"
  ) {
    const obj = parsed as { hours: number; startAt: number };
    return { hours: obj.hours, startAt: obj.startAt };
  }
  return { hours: 0, startAt: 0 };
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
 * Insert a new medication. Returns the inserted row in camelCase form.
 * Enqueues a `create` operation to the sync queue.
 */
export async function create(
  input: CreateMedicationInput
): Promise<Medication> {
  const db = await getDb();
  const now = Date.now();
  const id = generateId();
  const scheduleTimesJson = JSON.stringify(input.scheduleTimes);

  await db.runAsync(
    `INSERT INTO medications (
      id, user_id, name, rxcui, dosage,
      schedule_type, schedule_times,
      active, created_at, updated_at, sync_status
    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'pending')`,
    id,
    input.userId,
    input.name,
    input.rxcui ?? null,
    input.dosage ?? null,
    input.scheduleType,
    scheduleTimesJson,
    now,
    now
  );

  const inserted = await get(id);
  if (inserted === null) {
    throw new Error("Failed to read inserted medication");
  }

  // Without this the new medication has no `reminders` row, and the scheduler
  // — which reads `reminders` — would never arm an alarm for it.
  await mirrorMedication(id);

  await enqueue("medication", id, "create", {
    localId: id,
    userId: input.userId,
    name: input.name,
    rxcui: input.rxcui ?? null,
    dosage: input.dosage ?? null,
    scheduleType: input.scheduleType,
    scheduleTimes: input.scheduleTimes,
    active: true,
    createdAt: now,
    updatedAt: now,
  });

  return inserted;
}

/**
 * List all active medications for a user, newest first.
 */
export async function list(userId: string): Promise<readonly Medication[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<MedicationRow>(
    `SELECT * FROM medications
     WHERE user_id = ? AND active = 1
     ORDER BY created_at DESC`,
    userId
  );
  return rows.map(rowToMedication);
}

/**
 * Fetch a single medication by id. Returns `null` if not found.
 */
export async function get(id: string): Promise<Medication | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<MedicationRow>(
    "SELECT * FROM medications WHERE id = ?",
    id
  );
  return row === null ? null : rowToMedication(row);
}

/**
 * Update a medication. Bumps `updated_at` and marks `sync_status = 'pending'`.
 * Enqueues an `update` operation to the sync queue.
 */
export async function update(
  id: string,
  patch: UpdateMedicationPatch
): Promise<Medication> {
  const db = await getDb();
  const existing = await get(id);
  if (existing === null) {
    throw new Error(`Medication ${id} not found`);
  }

  const now = Date.now();
  const nextName = patch.name ?? existing.name;
  const nextRxcui = patch.rxcui ?? existing.rxcui;
  const nextDosage = patch.dosage ?? existing.dosage;
  const nextScheduleType = patch.scheduleType ?? existing.scheduleType;
  const nextScheduleTimes = patch.scheduleTimes ?? existing.scheduleTimes;
  const nextActive = patch.active ?? existing.active;

  await db.runAsync(
    `UPDATE medications
     SET name = ?, rxcui = ?, dosage = ?,
         schedule_type = ?, schedule_times = ?,
         active = ?, updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    nextName,
    nextRxcui ?? null,
    nextDosage ?? null,
    nextScheduleType,
    JSON.stringify(nextScheduleTimes),
    nextActive ? 1 : 0,
    now,
    id
  );

  const updated = await get(id);
  if (updated === null) {
    throw new Error(`Medication ${id} disappeared after update`);
  }

  await mirrorMedication(id);

  await enqueue("medication", id, "update", {
    localId: id,
    userId: updated.userId,
    name: updated.name,
    rxcui: updated.rxcui ?? null,
    dosage: updated.dosage ?? null,
    scheduleType: updated.scheduleType,
    scheduleTimes: updated.scheduleTimes,
    active: updated.active,
    createdAt: updated.createdAt,
    updatedAt: now,
  });

  return updated;
}

/**
 * Soft-delete a medication: sets `active = 0` and bumps `updated_at`.
 * Enqueues a `delete` operation to the sync queue.
 */
export async function softDelete(id: string): Promise<void> {
  const db = await getDb();
  const existing = await get(id);
  if (existing === null) {
    return;
  }

  const now = Date.now();
  await db.runAsync(
    `UPDATE medications
     SET active = 0, updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    now,
    id
  );

  await mirrorMedicationDelete(id, now);

  await enqueue("medication", id, "delete", {
    localId: id,
    userId: existing.userId,
    deletedAt: now,
  });
}

/**
 * Mark a medication's sync status as `synced`.
 */
export async function markSynced(id: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE medications SET sync_status = 'synced' WHERE id = ?",
    id
  );
  // No mirror call. Since the projection stopped inheriting the medication's
  // sync columns, nothing it stores derives from `sync_status` — mirroring here
  // would rewrite the row to its existing value, re-pend it, and enqueue a push
  // from the function whose whole job is recording that a push succeeded.
}

/**
 * Mark a medication's sync status as `error` and store the last error.
 */
export async function markError(id: string, error: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE medications SET sync_status = 'error' WHERE id = ?",
    id
  );
  // No mirror call, for the same reason as `markSynced`.
  console.error(`[medications] sync error for ${id}: ${error}`);
}

/**
 * Store the Convex-generated id for a local medication row.
 */
export async function setConvexId(
  localId: string,
  convexId: string
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    "UPDATE medications SET convex_id = ? WHERE id = ?",
    convexId,
    localId
  );
  // No mirror call. This used to propagate the document id into the projection
  // "so the bridge keeps resolving under either identity" — but that identity
  // was an `Id<"medications">` being written into a column the sync worker
  // reads as an `Id<"reminders">`, which is the defect migration 007 repairs.
  // The projection's `convex_id` now comes only from `remindersRepo.setConvexId`
  // after a genuine `api.reminders.create`, so there is nothing to propagate.
}

/**
 * Look up the Convex id for a local medication row.
 */
export async function getConvexId(localId: string): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ convex_id: string | null }>(
    "SELECT convex_id FROM medications WHERE id = ?",
    localId
  );
  return row?.convex_id ?? null;
}

/**
 * Look up the local medication id for a Convex document id.
 *
 * The inverse of `getConvexId`, and it exists for the pull path. `push`
 * converts a local id to a Convex id before every mutation
 * (`worker.resolveMedicationId`), but the reverse direction had no equivalent:
 * `pull.ts` mapped a dose log's `_id` back through `localId` while copying
 * `medicationId` — an `Id<"medications">` — across untouched, so every pulled
 * dose log landed with a Convex document id in a column that every other
 * producer fills with a local UUID.
 *
 * That column is a join key, which is what made it costly rather than
 * cosmetic. `DOSE_LOG_TO_INSTANCE_SELECT` guards on
 * `EXISTS (SELECT 1 FROM reminders r WHERE r.id = d.medication_id)`, and a
 * Convex id matches no `reminders.id`, so the mirror silently projected
 * nothing for pulled rows — and since the read cutover the dose history screen
 * reads only the projection. A restored or second device showed an empty
 * history while `dose_logs` held every row.
 *
 * Served by `idx_medications_convex_id` (migration 002).
 *
 * Returns `null` when this device has not yet pulled the parent medication,
 * which is a real and ordinary state: the two subscriptions land
 * independently and the dose logs can arrive first.
 */
export async function localIdForConvexId(
  convexId: string
): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ id: string }>(
    "SELECT id FROM medications WHERE convex_id = ?",
    convexId
  );
  return row?.id ?? null;
}

/**
 * Read all medications for a user directly from SQLite (offline-first).
 */
export async function selectAll(
  userId: string
): Promise<readonly Medication[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<MedicationRow>(
    `SELECT * FROM medications
     WHERE user_id = ? AND active = 1
     ORDER BY created_at DESC`,
    userId
  );
  return rows.map(rowToMedication);
}

/**
 * Upsert many medications from a remote pull. Skips rows where the remote
 * `updatedAt` is not newer than the local `updated_at` (last-write-wins).
 */
export async function upsertMany(rows: readonly Medication[]): Promise<number> {
  const db = await getDb();
  let updated = 0;

  for (const remote of rows) {
    const local = await get(remote.id);
    if (!shouldApplyRemoteMedication(local, remote)) {
      continue;
    }

    await db.runAsync(
      `INSERT INTO medications (
        id, user_id, name, rxcui, dosage,
        schedule_type, schedule_times,
        active, created_at, updated_at, sync_status
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'synced')
      ON CONFLICT(id) DO UPDATE SET
        user_id = excluded.user_id,
        name = excluded.name,
        rxcui = excluded.rxcui,
        dosage = excluded.dosage,
        schedule_type = excluded.schedule_type,
        schedule_times = excluded.schedule_times,
        active = excluded.active,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at,
        sync_status = 'synced'`,
      remote.id,
      remote.userId,
      remote.name,
      remote.rxcui ?? null,
      remote.dosage ?? null,
      remote.scheduleType,
      JSON.stringify(remote.scheduleTimes),
      remote.active ? 1 : 0,
      remote.createdAt,
      remote.updatedAt
    );
    // A medication arriving from a remote pull needs an alarm just as much as
    // one typed on this device — but it does not need pushing back, so this
    // refreshes the row without re-pending it. See `mirrorMedicationRow`.
    //
    // Caught per row for the reason `doseLogs.upsertMany` gives: the
    // medication is already committed, so aborting here would leave every
    // later row in the batch unprojected too. `repairMissingProjections`
    // retries whatever lands in this branch on the next drain.
    try {
      await mirrorMedicationRow(remote.id);
    } catch (error: unknown) {
      console.error(
        `[medications] failed to mirror pulled medication ${remote.id}:`,
        error
      );
    }
    updated += 1;
  }

  return updated;
}
