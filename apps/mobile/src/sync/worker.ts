import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api, type Id } from "@taskrx/convex";
import type {
  DoseStatus,
  InstanceStatus,
  Priority,
  ReminderKind,
  Weekday,
} from "@taskrx/shared";
import type { ConvexReactClient } from "convex/react";

import { convex } from "../api/convex";
import * as doseLogMirror from "../db/repos/dose-log-mirror";
import * as doseLogsRepo from "../db/repos/doseLogs";
import * as medicationMirror from "../db/repos/medication-mirror";
import * as medicationsRepo from "../db/repos/medications";
import * as reminderInstancesRepo from "../db/repos/reminderInstances";
import * as remindersRepo from "../db/repos/reminders";
import * as syncQueueRepo from "../db/repos/syncQueue";

import type { SyncQueueEntry, SyncResult, SyncStatus } from "./types";

/**
 * Push sync worker.
 *
 * Drains the local SQLite `sync_queue` by calling the matching Convex
 * mutation for each entry. Retries failed entries with exponential backoff
 * and pauses when the device is offline.
 *
 * The worker maps stable local UUIDs to Convex-generated document ids using
 * the `convex_id` columns added in migration 002. This is required because
 * Convex assigns its own `_id` on insert, while local SQLite uses UUIDs as
 * primary keys.
 */

const MAX_ATTEMPTS = 5;
const BACKOFF_BASE_MS = 1000;
const BACKOFF_MAX_MS = 30000;

type StatusListener = (status: SyncStatus) => void;

interface MedicationPayload {
  readonly localId: string;
  readonly userId: string;
  readonly name: string;
  readonly rxcui: string | null;
  readonly dosage: string | null;
  readonly scheduleType: "daily" | "interval";
  readonly scheduleTimes: unknown;
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deletedAt?: number;
}

interface DoseLogPayload {
  readonly localId: string;
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
  readonly status: DoseStatus;
  readonly takenAt: number | null;
  readonly notes: string | null;
  readonly createdAt: number;
  readonly updatedAt: number;
}

/**
 * The `schedule` field as it round-trips through the queue payload.
 *
 * Structurally identical to `ScheduleTimes` from `@taskrx/shared`, but with
 * mutable arrays: the domain type declares `readonly string[]`, and Convex's
 * generated argument type — built from `v.array(...)` in
 * `packages/convex/convex/schema.ts` — is `string[]`, which a `readonly` array
 * is not assignable to. Since this value is the product of `JSON.parse`, the
 * mutable spelling is also the honest one.
 */
type ReminderSchedulePayload =
  | { readonly type: "daily"; readonly times: string[] }
  | {
      readonly type: "interval";
      readonly hours: number;
      readonly startAt: number;
    }
  | {
      readonly type: "weekly";
      readonly daysOfWeek: Weekday[];
      readonly times: string[];
    };

/**
 * Payload written by `db/repos/reminders.ts`. As with `MedicationPayload`, a
 * `delete` entry carries only `localId`, `userId`, and `deletedAt`; the delete
 * branch reads nothing else.
 */
interface ReminderPayload {
  readonly localId: string;
  readonly userId: string;
  readonly kind: ReminderKind;
  readonly name: string;
  readonly rxcui: string | null;
  readonly dosage: string | null;
  readonly instructions: string | null;
  readonly schedule: ReminderSchedulePayload;
  readonly priority: Priority;
  readonly tags: string[];
  readonly pillsRemaining: number | null;
  readonly pillsPerDose: number | null;
  readonly refillReminderDays: number | null;
  readonly caregiverIds: string[];
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deletedAt?: number;
}

/** Payload written by `db/repos/reminderInstances.ts`. */
interface ReminderInstancePayload {
  readonly localId: string;
  readonly userId: string;
  readonly reminderId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
  readonly status: InstanceStatus;
  readonly takenAt: number | null;
  readonly snoozedUntil: number | null;
  readonly snoozeCount: number;
  readonly notes: string | null;
  readonly createdAt: number;
  readonly updatedAt: number;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

function formatError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

export class SyncWorker {
  private readonly client: ConvexReactClient;
  private readonly listeners: Set<StatusListener> = new Set<StatusListener>();
  private status: SyncStatus = "idle";
  private netInfoUnsubscribe: (() => void) | null = null;
  private draining = false;

  constructor(client: ConvexReactClient = convex) {
    this.client = client;
  }

  get currentStatus(): SyncStatus {
    return this.status;
  }

  subscribe(listener: StatusListener): () => void {
    this.listeners.add(listener);
    listener(this.status);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private setStatus(next: SyncStatus): void {
    if (this.status === next) {
      return;
    }
    this.status = next;
    for (const listener of this.listeners) {
      listener(next);
    }
  }

  start(): void {
    this.stop();

    this.netInfoUnsubscribe = NetInfo.addEventListener(
      (state: NetInfoState) => {
        if (state.isConnected) {
          if (this.status === "offline") {
            this.setStatus("idle");
          }
          void this.drain();
        } else {
          this.setStatus("offline");
        }
      }
    );

    void this.drain();
  }

  stop(): void {
    this.netInfoUnsubscribe?.();
    this.netInfoUnsubscribe = null;
  }

  async drain(): Promise<void> {
    if (this.draining) {
      return;
    }
    // Claimed before the first await. `start()` fires `void this.drain()` while
    // the NetInfo listener may fire another; with the flag set after the fetch,
    // both cleared the guard and drained the same snapshot concurrently.
    //
    // Everything after this line MUST stay inside the try whose `finally`
    // releases the flag -- including the offline and connectivity-failure
    // returns. An earlier shape returned from a separate inner try, so those
    // paths never released it: launching the app offline left `draining` set
    // and every later drain short-circuited above, for the life of the process.
    this.draining = true;

    try {
      let connected = false;
      try {
        connected = (await NetInfo.fetch()).isConnected === true;
      } catch (error: unknown) {
        // Handled rather than rethrown. A throw from here escaped the
        // `void this.drain()` call sites as an unhandled rejection and skipped
        // the status update every other drain failure performs.
        console.error("[sync] connectivity check failed:", error);
        this.setStatus("error");
        return;
      }
      if (!connected) {
        this.setStatus("offline");
        return;
      }

      this.setStatus("syncing");

      // Ahead of everything else, rebuild projections that were never
      // written. A mirror can throw — the pull path catches it per row so one
      // bad row does not take the batch down — and nothing downstream repairs
      // the result: the conflict rules reject the re-pulled row and
      // `reconcilePending` only re-enqueues rows that already exist. Since the
      // read cutover a missing projection is a medication gone from the med
      // list or a dose gone from history, so this runs before the reconcile
      // passes that would otherwise have nothing to find.
      //
      // Medications first: an instance's insert is dropped by the EXISTS guard
      // while its parent has no `reminders` row, so repairing parents first is
      // what lets both land in one drain rather than two.
      //
      // Both are best-effort for the same reason the reconciles are — a queue
      // that already has entries is still worth draining.
      try {
        await medicationMirror.repairMissingProjections();
      } catch (error) {
        console.error("[sync] medication projection repair failed", error);
      }

      try {
        await doseLogMirror.repairMissingProjections();
      } catch (error) {
        console.error("[sync] dose log projection repair failed", error);
      }

      // Before reading the queue, give reminders that are pending but absent
      // from it a queue entry. Migration 005 backfilled them in SQL, which
      // cannot enqueue, and a best-effort enqueue can fail after its row
      // write; in both cases nothing else would ever push the row. Migration
      // 008 indexes both sides of that query, so it is cheap once the backlog
      // is clear; without those indexes it full-scans `reminders` and rescans
      // `sync_queue` per row, on every connectivity change.
      //
      // Failing here must not cost the drain: the queue as it already stands
      // is still worth draining, and the next drain retries the reconcile.
      try {
        await remindersRepo.reconcilePending();
      } catch (error) {
        console.error("[sync] reminder reconciliation failed", error);
      }

      // Instances after reminders, never the other way round: an instance
      // `create` resolves its parent through `resolveReminderId`, which throws
      // while the reminder still has no `convex_id`, so the parent's entry has
      // to be in the queue ahead of the child's. `syncQueueRepo.pending()`
      // orders by `created_at` (served by `idx_sync_queue_created`) and these
      // two passes enqueue in sequence, so a backfilled parent sorts ahead of
      // its own children inside this drain.
      //
      // That ordering buys a clean first pass; it is not what keeps instances
      // out of the dead-letter queue, and it should not be relied on as if it
      // were. `created_at` is a millisecond stamp and both passes can land in
      // the same one, and equal keys have no defined order. When it does not
      // decide, the child fails once, `markFailed` spends one of five
      // attempts, and the next drain finds the parent already synced.
      try {
        await reminderInstancesRepo.reconcilePending();
      } catch (error) {
        console.error("[sync] reminder instance reconciliation failed", error);
      }

      const entries = await syncQueueRepo.pending();
      let failed = false;
      for (const entry of entries) {
        const result = await this.processEntry(entry);
        if (!result.ok) {
          await syncQueueRepo.markFailed(entry.id, result.error);
          if (entry.attempts + 1 >= MAX_ATTEMPTS) {
            await this.markSourceError(entry, result.error);
            await syncQueueRepo.markDeadLettered(entry.id);
          }
          failed = true;
          this.setStatus("error");
          await this.backoff(entry.attempts + 1);
          continue;
        }

        await syncQueueRepo.remove(entry.id);
        await this.markSourceSynced(entry);
      }

      // Derived from what this pass actually did, not from a fresh `pending()`
      // read. Anything enqueued while the loop ran is new work for the next
      // drain, not a failure of this one -- re-reading the queue reported
      // `error` after a completely clean sync.
      this.setStatus(failed ? "error" : "idle");
    } catch (error: unknown) {
      console.error("[sync] drain failed:", error);
      this.setStatus("error");
    } finally {
      this.draining = false;
    }
  }

  private async processEntry(entry: SyncQueueEntry): Promise<SyncResult> {
    try {
      switch (entry.entityType) {
        case "medication": {
          return await this.processMedicationEntry(entry);
        }
        case "dose_log": {
          return await this.processDoseLogEntry(entry);
        }
        case "reminder": {
          return await this.processReminderEntry(entry);
        }
        case "reminder_instance": {
          return await this.processReminderInstanceEntry(entry);
        }
        default: {
          return {
            ok: false,
            error: `Unknown entity type ${String(entry.entityType)}`,
          };
        }
      }
    } catch (error: unknown) {
      return { ok: false, error: formatError(error) };
    }
  }

  private async processMedicationEntry(
    entry: SyncQueueEntry
  ): Promise<SyncResult> {
    const payload = JSON.parse(entry.payload) as MedicationPayload;

    switch (entry.operation) {
      case "create": {
        const convexId = await this.client.mutation(api.medications.create, {
          localId: payload.localId,
          userId: payload.userId,
          name: payload.name,
          scheduleType: payload.scheduleType,
          scheduleTimes: payload.scheduleTimes,
          active: payload.active,
          createdAt: payload.createdAt,
          updatedAt: payload.updatedAt,
          ...(payload.rxcui !== null ? { rxcui: payload.rxcui } : {}),
          ...(payload.dosage !== null ? { dosage: payload.dosage } : {}),
        });
        await medicationsRepo.setConvexId(payload.localId, convexId);
        return { ok: true };
      }
      case "update": {
        const convexId = (await this.resolveMedicationId(
          payload.localId
        )) as Id<"medications">;
        await this.client.mutation(api.medications.update, {
          id: convexId,
          name: payload.name,
          scheduleType: payload.scheduleType,
          scheduleTimes: payload.scheduleTimes,
          active: payload.active,
          updatedAt: payload.updatedAt,
          ...(payload.rxcui !== null ? { rxcui: payload.rxcui } : {}),
          ...(payload.dosage !== null ? { dosage: payload.dosage } : {}),
        });
        return { ok: true };
      }
      case "delete": {
        const convexId = (await this.resolveMedicationId(
          payload.localId
        )) as Id<"medications">;
        await this.client.mutation(api.medications.softDelete, {
          id: convexId,
        });
        return { ok: true };
      }
      default: {
        return {
          ok: false,
          error: `Unknown operation ${String(entry.operation)}`,
        };
      }
    }
  }

  private async processDoseLogEntry(
    entry: SyncQueueEntry
  ): Promise<SyncResult> {
    const payload = JSON.parse(entry.payload) as DoseLogPayload;

    switch (entry.operation) {
      case "create": {
        const medicationConvexId = (await this.resolveMedicationId(
          payload.medicationId
        )) as Id<"medications">;
        const convexId = await this.client.mutation(api.doseLogs.create, {
          localId: payload.localId,
          userId: payload.userId,
          medicationId: medicationConvexId,
          scheduledTime: payload.scheduledTime,
          status: payload.status,
          createdAt: payload.createdAt,
          updatedAt: payload.updatedAt,
          ...(payload.takenAt !== null ? { takenAt: payload.takenAt } : {}),
          ...(payload.notes !== null ? { notes: payload.notes } : {}),
        });
        await doseLogsRepo.setConvexId(payload.localId, convexId);
        return { ok: true };
      }
      case "update": {
        const convexId = (await this.resolveDoseLogId(
          payload.localId
        )) as Id<"doseLogs">;
        switch (payload.status) {
          case "taken": {
            await this.client.mutation(api.doseLogs.markTaken, {
              id: convexId,
              ...(payload.takenAt !== null ? { takenAt: payload.takenAt } : {}),
            });
            break;
          }
          case "skipped": {
            await this.client.mutation(api.doseLogs.markSkipped, {
              id: convexId,
              ...(payload.notes !== null ? { notes: payload.notes } : {}),
            });
            break;
          }
          case "missed": {
            await this.client.mutation(api.doseLogs.markMissed, {
              id: convexId,
            });
            break;
          }
          case "pending": {
            return { ok: false, error: "Cannot sync pending dose log update" };
          }
          default: {
            return {
              ok: false,
              error: `Unknown dose status ${String(payload.status)}`,
            };
          }
        }
        return { ok: true };
      }
      default: {
        return { ok: false, error: `Unknown operation ${entry.operation}` };
      }
    }
  }

  /**
   * Unified reminders. The `medication` branch above stays in place: both
   * tables are still written during the cutover, and a queue entry names the
   * table it came from.
   *
   * Field clearing does not round-trip. `api.reminders.update` spells every
   * optional as `v.optional(...)` with no null member, so "leave alone" and
   * "clear" share one encoding; a null here has to be omitted, which reads as
   * "leave alone". A locally cleared `dosage` therefore stays set on Convex.
   * That is the same limitation `processMedicationEntry` has for `rxcui` and
   * `dosage`, and fixing it means adding a nullable member to the Convex
   * validator, not changing this file.
   */
  private async processReminderEntry(
    entry: SyncQueueEntry
  ): Promise<SyncResult> {
    const payload = JSON.parse(entry.payload) as ReminderPayload;

    switch (entry.operation) {
      case "create": {
        const convexId = await this.client.mutation(api.reminders.create, {
          localId: payload.localId,
          userId: payload.userId,
          kind: payload.kind,
          name: payload.name,
          schedule: payload.schedule,
          priority: payload.priority,
          tags: payload.tags,
          caregiverIds: payload.caregiverIds,
          active: payload.active,
          createdAt: payload.createdAt,
          updatedAt: payload.updatedAt,
          ...(payload.rxcui !== null ? { rxcui: payload.rxcui } : {}),
          ...(payload.dosage !== null ? { dosage: payload.dosage } : {}),
          ...(payload.instructions !== null
            ? { instructions: payload.instructions }
            : {}),
          ...(payload.pillsRemaining !== null
            ? { pillsRemaining: payload.pillsRemaining }
            : {}),
          ...(payload.pillsPerDose !== null
            ? { pillsPerDose: payload.pillsPerDose }
            : {}),
          ...(payload.refillReminderDays !== null
            ? { refillReminderDays: payload.refillReminderDays }
            : {}),
        });
        await remindersRepo.setConvexId(payload.localId, convexId);
        return { ok: true };
      }
      case "update": {
        const convexId = await this.resolveReminderId(payload.localId);
        await this.client.mutation(api.reminders.update, {
          id: convexId,
          name: payload.name,
          schedule: payload.schedule,
          priority: payload.priority,
          tags: payload.tags,
          caregiverIds: payload.caregiverIds,
          active: payload.active,
          updatedAt: payload.updatedAt,
          ...(payload.rxcui !== null ? { rxcui: payload.rxcui } : {}),
          ...(payload.dosage !== null ? { dosage: payload.dosage } : {}),
          ...(payload.instructions !== null
            ? { instructions: payload.instructions }
            : {}),
          ...(payload.pillsRemaining !== null
            ? { pillsRemaining: payload.pillsRemaining }
            : {}),
          ...(payload.pillsPerDose !== null
            ? { pillsPerDose: payload.pillsPerDose }
            : {}),
          ...(payload.refillReminderDays !== null
            ? { refillReminderDays: payload.refillReminderDays }
            : {}),
        });
        return { ok: true };
      }
      case "delete": {
        const convexId = await this.resolveReminderId(payload.localId);
        await this.client.mutation(api.reminders.softDelete, { id: convexId });
        return { ok: true };
      }
      default: {
        return {
          ok: false,
          error: `Unknown operation ${String(entry.operation)}`,
        };
      }
    }
  }

  /**
   * Reminder instances. There is no `delete` operation because the repository
   * never deletes an instance — a removal is a status change, which arrives
   * here as an `update`.
   */
  private async processReminderInstanceEntry(
    entry: SyncQueueEntry
  ): Promise<SyncResult> {
    const payload = JSON.parse(entry.payload) as ReminderInstancePayload;

    switch (entry.operation) {
      case "create": {
        // The parent has to exist on Convex first: `reminderInstances.create`
        // takes an `Id<"reminders">` and reads `kind` off the parent document.
        const reminderConvexId = await this.resolveReminderId(
          payload.reminderId
        );
        const convexId = await this.client.mutation(
          api.reminderInstances.create,
          {
            localId: payload.localId,
            userId: payload.userId,
            reminderId: reminderConvexId,
            scheduledTime: payload.scheduledTime,
            status: payload.status,
            snoozeCount: payload.snoozeCount,
            createdAt: payload.createdAt,
            updatedAt: payload.updatedAt,
            ...(payload.takenAt !== null ? { takenAt: payload.takenAt } : {}),
            ...(payload.snoozedUntil !== null
              ? { snoozedUntil: payload.snoozedUntil }
              : {}),
            ...(payload.notes !== null ? { notes: payload.notes } : {}),
          }
        );
        await reminderInstancesRepo.setConvexId(payload.localId, convexId);
        return { ok: true };
      }
      case "update": {
        const convexId = await this.resolveReminderInstanceId(payload.localId);
        switch (payload.status) {
          case "taken": {
            await this.client.mutation(api.reminderInstances.markTaken, {
              id: convexId,
              ...(payload.takenAt !== null ? { takenAt: payload.takenAt } : {}),
            });
            break;
          }
          case "snoozed": {
            if (payload.snoozedUntil === null) {
              return {
                ok: false,
                error: "Snoozed instance is missing snoozedUntil",
              };
            }
            // `snooze` increments `snoozeCount` server-side rather than taking
            // the local count, so two offline devices both land.
            await this.client.mutation(api.reminderInstances.snooze, {
              id: convexId,
              snoozedUntil: payload.snoozedUntil,
            });
            break;
          }
          case "skipped": {
            await this.client.mutation(api.reminderInstances.markSkipped, {
              id: convexId,
              ...(payload.notes !== null ? { notes: payload.notes } : {}),
            });
            break;
          }
          case "missed": {
            await this.client.mutation(api.reminderInstances.markMissed, {
              id: convexId,
            });
            break;
          }
          case "pending": {
            return {
              ok: false,
              error: "Cannot sync pending reminder instance update",
            };
          }
          default: {
            return {
              ok: false,
              error: `Unknown instance status ${String(payload.status)}`,
            };
          }
        }
        return { ok: true };
      }
      default: {
        // Narrows to 'delete', which the repository never enqueues for an
        // instance — a removal is a status change.
        return { ok: false, error: `Unknown operation ${entry.operation}` };
      }
    }
  }

  private async resolveMedicationId(localId: string): Promise<string> {
    const cached = await medicationsRepo.getConvexId(localId);
    if (cached !== null) {
      return cached;
    }

    const remote = await this.client.query(api.medications.getByLocalId, {
      localId,
    });
    if (remote === null) {
      throw new Error(`Medication ${localId} not found on Convex`);
    }

    await medicationsRepo.setConvexId(localId, remote._id);
    return remote._id;
  }

  private async resolveDoseLogId(localId: string): Promise<string> {
    const cached = await doseLogsRepo.getConvexId(localId);
    if (cached !== null) {
      return cached;
    }

    const remote = await this.client.query(api.doseLogs.getByLocalId, {
      localId,
    });
    if (remote === null) {
      throw new Error(`Dose log ${localId} not found on Convex`);
    }

    await doseLogsRepo.setConvexId(localId, remote._id);
    return remote._id;
  }

  /**
   * Resolve a local reminder id to its Convex document id.
   *
   * The return type is branded rather than a bare `string` so callers cannot
   * re-brand it themselves. That matters here more than it looks: until
   * migration 007, `reminders.convex_id` could hold an `Id<"medications">`,
   * because the medication mirror and migration 005 copied the column across
   * from `medications`. Every call site then wrote `as Id<"reminders">`, which
   * is exactly the assertion that made the wrong-table id typecheck. With the
   * brand applied once, here, the only unchecked step left is this cache read.
   *
   * The invariant it rests on: `reminders.convex_id` is written by
   * `remindersRepo.setConvexId` and by nothing else, and every caller of that
   * passes an id returned by an `api.reminders.*` mutation or query. The
   * mirror no longer touches the column at all, and 007 cleared the values it
   * had already written.
   */
  private async resolveReminderId(localId: string): Promise<Id<"reminders">> {
    const cached = await remindersRepo.getConvexId(localId);
    if (cached !== null) {
      return cached as Id<"reminders">;
    }

    const remote = await this.client.query(api.reminders.getByLocalId, {
      localId,
    });
    if (remote === null) {
      throw new Error(`Reminder ${localId} not found on Convex`);
    }

    await remindersRepo.setConvexId(localId, remote._id);
    return remote._id;
  }

  /**
   * As `resolveReminderId`, for instances. `reminder_instances.convex_id` had
   * the same contamination — migration 005 copied `dose_logs.convex_id` into
   * it, so it could hold an `Id<"doseLogs">` — and 007 clears that too.
   */
  private async resolveReminderInstanceId(
    localId: string
  ): Promise<Id<"reminderInstances">> {
    const cached = await reminderInstancesRepo.getConvexId(localId);
    if (cached !== null) {
      return cached as Id<"reminderInstances">;
    }

    const remote = await this.client.query(api.reminderInstances.getByLocalId, {
      localId,
    });
    if (remote === null) {
      throw new Error(`Reminder instance ${localId} not found on Convex`);
    }

    await reminderInstancesRepo.setConvexId(localId, remote._id);
    return remote._id;
  }

  // The two mark-source helpers switch rather than branch on `medication`:
  // with four entity types an `else` would have routed every reminder entry
  // into `doseLogsRepo`, whose UPDATE would match no row and silently leave
  // the reminder's `sync_status` stuck at 'pending'.
  private async markSourceSynced(entry: SyncQueueEntry): Promise<void> {
    try {
      switch (entry.entityType) {
        case "medication":
          await medicationsRepo.markSynced(entry.entityId);
          break;
        case "dose_log":
          await doseLogsRepo.markSynced(entry.entityId);
          break;
        case "reminder":
          await remindersRepo.markSynced(entry.entityId);
          break;
        case "reminder_instance":
          await reminderInstancesRepo.markSynced(entry.entityId);
          break;
      }
    } catch (error: unknown) {
      console.error("[sync] failed to mark source synced:", error);
    }
  }

  private async markSourceError(
    entry: SyncQueueEntry,
    error: string
  ): Promise<void> {
    try {
      switch (entry.entityType) {
        case "medication":
          await medicationsRepo.markError(entry.entityId, error);
          break;
        case "dose_log":
          await doseLogsRepo.markError(entry.entityId, error);
          break;
        case "reminder":
          await remindersRepo.markError(entry.entityId, error);
          break;
        case "reminder_instance":
          await reminderInstancesRepo.markError(entry.entityId, error);
          break;
      }
    } catch (markError: unknown) {
      console.error("[sync] failed to mark source error:", markError);
    }
  }

  private async backoff(attempts: number): Promise<void> {
    const delay = Math.min(
      BACKOFF_BASE_MS * 2 ** (attempts - 1),
      BACKOFF_MAX_MS
    );
    await sleep(delay);
  }
}

export const syncWorker = new SyncWorker();
