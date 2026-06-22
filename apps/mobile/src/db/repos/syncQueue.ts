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
 * Return all pending queue entries, oldest first. The sync worker drains
 * these in order.
 */
export async function pending(): Promise<readonly SyncQueueEntry[]> {
  const db = await getDb();
  const rows = await db.getAllAsync<SyncQueueRow>(
    "SELECT * FROM sync_queue ORDER BY created_at ASC"
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
