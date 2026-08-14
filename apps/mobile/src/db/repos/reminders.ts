import type {
  Priority,
  Reminder,
  ReminderKind,
  ScheduleTimes,
  Weekday,
} from "@taskrx/shared";
import { PRIORITIES, REMINDER_KINDS, noneTimes } from "@taskrx/shared";

import { getDb } from "../index";

import { generateId } from "./ids";
import { enqueue } from "./syncQueue";

/**
 * Local SQLite repository for unified reminders.
 *
 * The row shape is snake_case (matching `004_unified_reminders.sql`); this
 * module returns the camelCase `Reminder` from `@taskrx/shared`. Every reminder
 * on this device is written here — `meds/new.tsx` calls `create`, and since
 * migration 009 there is no other table it could go to.
 *
 * Every write enqueues a `sync_queue` row with entity_type='reminder' so the
 * sync worker can push it to Convex. That was not possible until
 * `006_widen_sync_queue.sql`: 001's `entity_type` CHECK admitted only the two
 * legacy kinds, SQLite cannot widen a CHECK without rebuilding the table, and
 * an enqueue would have thrown a constraint failure at runtime. 009 rebuilt it
 * once more, this time narrowing it to the two unified kinds.
 *
 * The enqueue is best-effort: it is not in the same transaction as the row
 * write, so a failure to enqueue leaves the row's `sync_status` at 'pending'
 * for a later reconciliation pass rather than rolling back a change the user
 * already made.
 *
 * The `schedule`, `tags`, and `caregiver_ids` columns hold JSON. Reads parse
 * defensively — a column can only be as well-formed as whatever wrote it, and
 * a malformed blob must not take down the medication list.
 *
 * `rxcui` is storage-only. It is not on the domain `Reminder` (the domain does
 * not model RxNorm) but the column exists locally and server-side, which is
 * what let the RxNorm code survive the retirement of the legacy table. It is
 * writable through `create`/`update` and readable through `getRxcui`, the same
 * shape of accessor `convex_id` gets, rather than appearing on the returned
 * object.
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
  /**
   * The row's id, when the caller already has one to honour.
   *
   * Omitted by every hand-entry surface — there the id is this repo's to mint.
   * Supplied by the refill raise, where `planRefill` has already built the whole
   * `Reminder` being written, id included: generating a second one here would
   * store a row that is not the one the pure function decided on.
   */
  readonly id?: string;
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
 * An unrecognized or malformed blob degrades to `none` rather than throwing: a
 * crash on the list screen is not recoverable, and a row whose schedule cannot
 * be read has, as far as this device can tell, no schedule.
 *
 * It used to degrade to an empty daily schedule. That is precisely the
 * misconfiguration the `none` variant was added to replace — it produces no
 * Occurrences while claiming to be a daily schedule — and it also stranded the
 * row: there is no edit screen for an existing reminder, so the Backlog's "give
 * it a time" is the only way back. Degrading to `none` puts a corrupt row
 * exactly where it can be repaired.
 */
function parseSchedule(json: string): ScheduleTimes {
  const parsed = parseJson(json);
  if (!isRecord(parsed)) {
    return noneTimes();
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
    case "once":
      // A `once` with no readable instant names no moment at all, so it lands
      // with the other unreadable blobs above.
      return typeof parsed.at === "number"
        ? { type: "once", at: parsed.at }
        : noneTimes();
    case "none":
      return noneTimes();
    default:
      return noneTimes();
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
  const id = input.id ?? generateId();

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

  // The row above stores the defaults because the columns are NOT NULL; the
  // payload does not, because `null` there means "the caller named no value"
  // and the Convex `create` supplies the same defaults itself. Sending the
  // local default instead would be indistinguishable from the caller choosing
  // it, which is the confusion `enqueueExternalWrite` documents below.
  await enqueue("reminder", id, "create", {
    localId: id,
    userId: input.userId,
    kind: input.kind,
    name: input.name,
    rxcui: input.rxcui ?? null,
    dosage: input.dosage ?? null,
    instructions: input.instructions ?? null,
    schedule: input.schedule,
    priority: input.priority ?? null,
    tags: input.tags ?? null,
    pillsRemaining: input.pillsRemaining ?? null,
    pillsPerDose: input.pillsPerDose ?? null,
    refillReminderDays: input.refillReminderDays ?? null,
    caregiverIds: input.caregiverIds ?? null,
    active: true,
    createdAt: now,
    updatedAt: now,
  });

  return inserted;
}

export interface ListRemindersOptions {
  /**
   * Include reminders the user has stopped.
   *
   * Off by default, because every caller that asks "what should I arm / show as
   * live" wants active ones only. The timeline wants them on: stopping a
   * medication does not unmake the Reminder Instances already recorded against
   * it, and `projectTimeline` needs the parent to render one at all. It already
   * knows not to predict new Occurrences for an inactive reminder, so handing it
   * the full set is what keeps that rule in one place.
   */
  readonly includeInactive?: boolean;
}

/**
 * List a user's reminders, newest first. Pass `kind` to restrict to medications
 * or tasks; omit it for the combined list, which is the whole point of the
 * unified table.
 */
export async function list(
  userId: string,
  kind?: ReminderKind,
  options: ListRemindersOptions = {}
): Promise<readonly Reminder[]> {
  const db = await getDb();
  const conditions = ["user_id = ?"];
  const args: (string | number)[] = [userId];

  if (options.includeInactive !== true) {
    conditions.push("active = 1");
  }
  if (kind !== undefined) {
    conditions.push("kind = ?");
    args.push(kind);
  }

  const rows = await db.getAllAsync<ReminderRow>(
    `SELECT * FROM reminders
     WHERE ${conditions.join(" AND ")}
     ORDER BY created_at DESC`,
    ...args
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

  // `patch`, not `updated`, for the three server-owned fields: the patch is the
  // only evidence that this caller meant to set one. `updated.priority` is the
  // column, and on a row backfilled by migration 005 the column is that
  // migration's default, so reading it here would push `normal`/`[]`/`[]` over
  // whatever the server holds on every unrelated edit.
  await enqueue("reminder", id, "update", {
    localId: id,
    userId: updated.userId,
    kind: updated.kind,
    name: updated.name,
    rxcui: nextRxcui ?? null,
    dosage: updated.dosage ?? null,
    instructions: updated.instructions ?? null,
    schedule: updated.schedule,
    priority: patch.priority ?? null,
    tags: patch.tags ?? null,
    pillsRemaining: updated.pillsRemaining ?? null,
    pillsPerDose: updated.pillsPerDose ?? null,
    refillReminderDays: updated.refillReminderDays ?? null,
    caregiverIds: patch.caregiverIds ?? null,
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
  // Read first: a delete for a row that is not here is not a change to
  // propagate, and the enqueued payload needs the owning user.
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
 * Enqueue a sync entry for a `reminders` row that was written *outside* this
 * repo.
 *
 * Two writers used to bypass `create`/`update`: the medication mirror's raw-SQL
 * upsert and migration 005's backfill. The mirror is deleted; 005 remains,
 * because an install upgrading from a pre-cutover build still replays it, and a
 * `.sql` file cannot call TypeScript to enqueue. Without this, a backfilled
 * reminder would never produce a `sync_queue` row and would sit on the device
 * unsynced forever.
 *
 * The payload is built here rather than at the call sites so there is exactly
 * one definition of a reminder's sync shape.
 *
 * The operation is chosen from `convex_id` rather than passed in: a row the
 * server has never seen needs `create`, and one it has needs `update`. Getting
 * this backwards is not a cosmetic error — `processReminderEntry`'s `update`
 * branch calls `resolveReminderId`, which throws when the document does not
 * exist yet.
 *
 * A soft delete routes through here as an ordinary `update` carrying
 * `active: false`, never as a `delete`: a `delete` entry for a row that never
 * synced would fail id resolution for no reason.
 *
 * `priority`, `tags` and `caregiverIds` are sent as `null` — never read off the
 * row — because this is by definition the path for a row *no local writer
 * authored*. A backfilled row carries migration 004's column defaults, so
 * reading the column here could only ever transmit a default, and
 * `api.reminders.update` patches every field it receives: the effect was to
 * reset the server's copy on every push. A `null` says "no value to offer" and
 * leaves the server's alone.
 *
 * The cost is real rather than theoretical, because `create` above has callers
 * now (`meds/new.tsx`, `add.tsx`, the stress-seed screen, and the refill raise
 * in `src/refill/read.ts`). If one of their enqueues failed after the row write
 * and `reconcilePending` recovered the row through here, a chosen `priority`
 * would be dropped from that recovery — the column cannot distinguish a chosen
 * `normal` from a defaulted one.
 *
 * `add.tsx` was the first caller to accept that cost — its header argues the
 * trade — and the refill raise is the second. It writes `priority: 'high'`
 * because `planRefill` decided a prescription running out is not an ordinary
 * errand, and the consequence is the same bounded one: a Refill Reminder
 * recovered through this path reaches the server at the server's default
 * priority while the device keeps `high`. Nothing about the warning is lost —
 * the Reminder, its name and its Backlog membership all survive, and ADR 0003
 * derives Tier from Kind, so no scheduling decision reads `priority` anyway.
 * Closing the gap properly needs provenance the schema does not carry, and a
 * `sync_status`-style "authored locally" marker is the cheapest shape for it.
 *
 * A no-op for an absent row, matching `softDelete`: there is nothing to
 * describe and no user to attribute it to.
 */
export async function enqueueExternalWrite(id: string): Promise<void> {
  const existing = await get(id);
  if (existing === null) {
    return;
  }

  // One raw read rather than `getConvexId` + `getRxcui`. `get` maps to the
  // domain shape, which carries neither column, and `reconcilePending` runs
  // this over the whole backlog -- three round trips per row became 3N.
  const db = await getDb();
  const raw = await db.getFirstAsync<{
    readonly convex_id: string | null;
    readonly rxcui: string | null;
  }>("SELECT convex_id, rxcui FROM reminders WHERE id = ?", id);
  const convexId = raw?.convex_id ?? null;
  const rxcui = raw?.rxcui ?? null;

  await enqueue("reminder", id, convexId === null ? "create" : "update", {
    localId: id,
    userId: existing.userId,
    kind: existing.kind,
    name: existing.name,
    rxcui: rxcui ?? null,
    dosage: existing.dosage ?? null,
    instructions: existing.instructions ?? null,
    schedule: existing.schedule,
    priority: null,
    tags: null,
    pillsRemaining: existing.pillsRemaining ?? null,
    pillsPerDose: existing.pillsPerDose ?? null,
    refillReminderDays: existing.refillReminderDays ?? null,
    caregiverIds: null,
    active: existing.active,
    createdAt: existing.createdAt,
    updatedAt: existing.updatedAt,
  });
}

/**
 * Enqueue every reminder that is `pending` but has nothing in the queue to
 * make it un-pend, and return how many were enqueued.
 *
 * This is the "later reconciliation pass" the enqueue-is-best-effort note at
 * the top of this file refers to. It closes two gaps:
 *
 *   * Migration 005 backfilled `reminders` from the legacy table in SQL, which
 *     cannot enqueue. Migration 007 then returns those rows to `pending`.
 *     Without this pass, an upgraded install's backfilled reminders would sit
 *     unsynced until the user happened to edit each one, so the Convex
 *     `reminders` table would stay empty or partial indefinitely.
 *   * An enqueue that failed after its row write — the failure mode the repo
 *     header already anticipates — leaves a `pending` row with no queue entry.
 *     Nothing previously recovered from that.
 *
 * The `NOT EXISTS` is the whole safety argument: a row that already has a live
 * queue entry is skipped, so this cannot duplicate work, and running it on
 * every drain is a no-op once the backlog is clear — cheap because migration
 * 008 indexes `reminders(sync_status)` and `sync_queue(entity_type, entity_id,
 * dead_letter)`; without those it full-scans both.
 *
 * The `dead_letter = 0` clause is narrow but real. In the ordinary case it does
 * nothing: dead-lettering runs `markSourceError` first, which sets the row to
 * `'error'`, and the `sync_status = 'pending'` filter excludes it — so a row
 * the worker genuinely gave up on is not reconciled, and recovery needs a fresh
 * local edit. But `markSourceError` swallows its own failures while
 * `markDeadLettered` still runs, so `pending` row + dead-lettered entry is
 * reachable. Without the clause that row would be skipped forever, because its
 * only entry is one nothing will ever drain.
 *
 * Widening the status filter to include `'error'` would be worse, not better: a
 * dead-lettered entry has `dead_letter = 1`, so `NOT EXISTS` would hold and
 * this would re-enqueue the same doomed write on every single drain, forever.
 */
export async function reconcilePending(): Promise<number> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ readonly id: string }>(
    `SELECT r.id
     FROM reminders r
     WHERE r.sync_status = 'pending'
       AND NOT EXISTS (
         SELECT 1 FROM sync_queue q
         WHERE q.entity_type = 'reminder'
           AND q.entity_id = r.id
           AND q.dead_letter = 0
       )`
  );

  for (const row of rows) {
    await enqueueExternalWrite(row.id);
  }

  return rows.length;
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

/**
 * Look up the local reminder id for a Convex document id. Served by
 * `idx_reminders_convex_id` (migration 004).
 *
 * The inverse of `getConvexId`, and it exists for the pull path: a pulled
 * instance carries its parent as an `Id<"reminders">`, and every local producer
 * of `reminder_instances.reminder_id` writes a local UUID. Storing the Convex
 * id there would break the foreign key and orphan the row from the reminder it
 * belongs to — the same defect the deleted legacy repo had a `localIdForConvexId`
 * of its own to undo, one table over.
 *
 * Returns `null` when this device has not pulled the parent reminder yet, which
 * is ordinary rather than exceptional: the two subscriptions land independently
 * and the instances can arrive first.
 */
export async function localIdForConvexId(
  convexId: string
): Promise<string | null> {
  const db = await getDb();
  const row = await db.getFirstAsync<{ id: string }>(
    "SELECT id FROM reminders WHERE convex_id = ?",
    convexId
  );
  return row?.id ?? null;
}

/**
 * A reminder as it arrives from a remote pull: the domain shape plus the two
 * columns the domain type does not carry. `rxcui` is storage-only (see the
 * module header) and `convexId` is this device's handle on the server
 * document.
 */
export interface PulledReminder {
  readonly reminder: Reminder;
  readonly convexId: string;
  readonly rxcui: string | null;
}

/**
 * Apply a batch of reminders from a remote pull, returning how many were
 * written.
 *
 * Every row handed here is written. Which rows those are is decided by
 * `applyRemotePull` in `src/sync/pull.ts`, where `shouldApplyRemoteReminder`
 * runs — last-write-wins, local winning ties. The decision used to live in this
 * loop; it moved so a test could execute it, since everything below `getDb()`
 * is behind expo-sqlite and unreachable from a node test. See that function's
 * comment.
 *
 * Two things this does that its legacy twin did not, both deliberate:
 *
 *   * It writes `convex_id` from the pulled document. The deleted legacy
 *     upsert left that column alone, so on a device that had only ever
 *     *received* a row the column stayed NULL and the id lookup could never
 *     resolve — which is why its dose-log pull skipped rows indefinitely
 *     rather than transiently. Writing it here is what makes the instance pull
 *     resolve its parent on a second device at all.
 *   * It writes `kind`. The mirror excluded that column, because a projected
 *     row was always a medication and rewriting it could clobber a converted
 *     one; a genuine pull has the server's own value and is authoritative.
 *
 * No enqueue, and `sync_status = 'synced'`: this content arrived *from* the
 * server, so marking it pending would push it straight back, one entry per
 * reminder on every subscription fire.
 */
export async function upsertMany(
  rows: readonly PulledReminder[]
): Promise<number> {
  const db = await getDb();
  let applied = 0;

  for (const { reminder, convexId, rxcui } of rows) {
    await db.runAsync(
      `INSERT INTO reminders (
        id, user_id, kind, name, rxcui, dosage, instructions, schedule,
        priority, tags, pills_remaining, pills_per_dose, refill_reminder_days,
        caregiver_ids, active, created_at, updated_at, sync_status, convex_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'synced', ?)
      ON CONFLICT(id) DO UPDATE SET
        user_id              = excluded.user_id,
        kind                 = excluded.kind,
        name                 = excluded.name,
        rxcui                = excluded.rxcui,
        dosage               = excluded.dosage,
        instructions         = excluded.instructions,
        schedule             = excluded.schedule,
        priority             = excluded.priority,
        tags                 = excluded.tags,
        pills_remaining      = excluded.pills_remaining,
        pills_per_dose       = excluded.pills_per_dose,
        refill_reminder_days = excluded.refill_reminder_days,
        caregiver_ids        = excluded.caregiver_ids,
        active               = excluded.active,
        created_at           = excluded.created_at,
        updated_at           = excluded.updated_at,
        sync_status          = 'synced',
        convex_id            = excluded.convex_id`,
      reminder.id,
      reminder.userId,
      reminder.kind,
      reminder.name,
      rxcui,
      reminder.dosage ?? null,
      reminder.instructions ?? null,
      JSON.stringify(reminder.schedule),
      reminder.priority,
      JSON.stringify(reminder.tags),
      reminder.pillsRemaining ?? null,
      reminder.pillsPerDose ?? null,
      reminder.refillReminderDays ?? null,
      JSON.stringify(reminder.caregiverIds),
      reminder.active ? 1 : 0,
      reminder.createdAt,
      reminder.updatedAt,
      convexId
    );
    applied += 1;
  }

  return applied;
}
