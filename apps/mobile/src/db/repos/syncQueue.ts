import { getDb } from "../index";

/**
 * Local SQLite repository for the sync queue.
 *
 * The sync queue is an append-only log of pending writes that need to be
 * pushed to Convex. Every local mutation (medication create/update/delete,
 * dose log create/update) enqueues an entry here. The sync worker (Day 7)
 * drains the queue, calls the matching Convex mutation, and deletes the
 * entry on success.
 *
 * For Day 3 we only need `enqueue` and `pending` — the worker itself is
 * Day 7 work.
 */

export type SyncEntityType = "medication" | "dose_log";
export type SyncOperation = "create" | "update" | "delete";

export interface SyncQueueEntry {
  readonly id: number;
  readonly entityType: SyncEntityType;
  readonly entityId: string;
  readonly operation: SyncOperation;
  readonly payload: string;
  readonly attempts: number;
  readonly lastError: string | null;
  readonly createdAt: number;
  readonly deadLetter: boolean;
}

interface SyncQueueRow {
  readonly id: number;
  readonly entity_type: SyncEntityType;
  readonly entity_id: string;
  readonly operation: SyncOperation;
  readonly payload: string;
  readonly attempts: number;
  readonly last_error: string | null;
  readonly created_at: number;
  readonly dead_letter: number;
}

function rowToEntry(row: SyncQueueRow): SyncQueueEntry {
  return {
    id: row.id,
    entityType: row.entity_type,
    entityId: row.entity_id,
    operation: row.operation,
    payload: row.payload,
    attempts: row.attempts,
    lastError: row.last_error,
    createdAt: row.created_at,
    deadLetter: row.dead_letter !== 0,
  };
}

/**
 * Append a new entry to the sync queue. The payload is JSON-stringified
 * before insertion so the queue can store arbitrary shapes per entity.
 *
 * Best-effort: callers should not block user-facing flows on this. If the
 * enqueue fails, the source row's `sync_status` stays `pending` and a
 * future reconciliation pass can re-enqueue.
 */
export async function enqueue(
  entityType: SyncEntityType,
  entityId: string,
  operation: SyncOperation,
  payload: unknown
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, attempts, created_at)
     VALUES (?, ?, ?, ?, 0, ?)`,
    entityType,
    entityId,
    operation,
    JSON.stringify(payload),
    Date.now()
  );
}

/**
 * Return pending queue entries (non-dead-lettered), oldest first. The sync
 * worker drains these in order. Rows that were terminally failed via
 * `markDeadLettered` are excluded so the worker does not retry them
 * forever and the sync-status badge is not pinned to `error` indefinitely.
 */
export async function pending(): Promise<readonly SyncQueueEntry[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<SyncQueueRow>(
    "SELECT * FROM sync_queue WHERE dead_letter = 0 ORDER BY created_at ASC"
  );
  return rows.map(rowToEntry);
}

/**
 * Return dead-lettered entries for inspection. Never called by the worker;
 * exposed for debug UI and manual requeue flows. Rows remain in the queue
 * indefinitely — the choice to keep them is deliberate: a dropped
 * medication dose log is unacceptable data loss.
 */
export async function deadLettered(): Promise<readonly SyncQueueEntry[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<SyncQueueRow>(
    "SELECT * FROM sync_queue WHERE dead_letter = 1 ORDER BY created_at ASC"
  );
  return rows.map(rowToEntry);
}

/**
 * Remove a successfully processed entry from the sync queue.
 */
export async function remove(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync("DELETE FROM sync_queue WHERE id = ?", id);
}

/**
 * Increment the attempt count and store the last error for a failed entry.
 * This does NOT set the dead-letter flag; the worker calls `markDeadLettered`
 * separately when attempts reach the terminal threshold.
 */
export async function markFailed(id: number, error: string): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `UPDATE sync_queue
     SET attempts = attempts + 1, last_error = ?
     WHERE id = ?`,
    error,
    id
  );
}

/**
 * Move an entry to the dead-letter state. Called by the worker after
 * `MAX_ATTEMPTS` failures. The row is retained (not deleted) so it can be
 * inspected and manually requeued.
 */
export async function markDeadLettered(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync("UPDATE sync_queue SET dead_letter = 1 WHERE id = ?", id);
}

/**
 * Re-arm a dead-lettered entry: clear the dead-letter flag and reset the
 * attempt counter so the worker will retry it on its next drain. Exposed
 * for debug UI and manual recovery flows.
 */
export async function requeue(id: number): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `UPDATE sync_queue
     SET dead_letter = 0, attempts = 0, last_error = NULL
     WHERE id = ?`,
    id
  );
}
