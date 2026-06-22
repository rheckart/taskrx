import type {
  DailySchedule,
  IntervalSchedule,
  Medication,
  ScheduleType,
} from "@taskrx/shared";

import { getDb } from "../index";

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

  await enqueue("medication", id, "create", {
    id,
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

  await enqueue("medication", id, "update", {
    id,
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

  await enqueue("medication", id, "delete", {
    id,
    userId: existing.userId,
    deletedAt: now,
  });
}
