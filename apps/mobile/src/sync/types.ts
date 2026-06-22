/**
 * Sync layer types for the TaskRx mobile app.
 *
 * These types are local to the sync worker and pull hooks. The queue entry
 * shape is defined in the repository and re-exported here so callers only
 * need to import from this module.
 */

export type SyncStatus = "idle" | "syncing" | "error" | "offline";

export type {
  SyncEntityType,
  SyncOperation,
  SyncQueueEntry,
} from "../db/repos/syncQueue";

export type SyncResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly error: string };
