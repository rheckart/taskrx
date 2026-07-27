import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api, type Id } from "@taskrx/convex";
import type { DoseStatus } from "@taskrx/shared";
import type { ConvexReactClient } from "convex/react";

import { convex } from "../api/convex";
import * as doseLogsRepo from "../db/repos/doseLogs";
import * as medicationsRepo from "../db/repos/medications";
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

    const state = await NetInfo.fetch();
    if (!state.isConnected) {
      this.setStatus("offline");
      return;
    }

    this.draining = true;
    this.setStatus("syncing");

    try {
      const entries = await syncQueueRepo.pending();
      for (const entry of entries) {
        const result = await this.processEntry(entry);
        if (!result.ok) {
          await syncQueueRepo.markFailed(entry.id, result.error);
          if (entry.attempts + 1 >= MAX_ATTEMPTS) {
            await this.markSourceError(entry, result.error);
            await syncQueueRepo.markDeadLettered(entry.id);
          }
          this.setStatus("error");
          await this.backoff(entry.attempts + 1);
          continue;
        }

        await syncQueueRepo.remove(entry.id);
        await this.markSourceSynced(entry);
      }

      const remaining = await syncQueueRepo.pending();
      this.setStatus(remaining.length > 0 ? "error" : "idle");
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

  private async markSourceSynced(entry: SyncQueueEntry): Promise<void> {
    try {
      if (entry.entityType === "medication") {
        await medicationsRepo.markSynced(entry.entityId);
      } else {
        await doseLogsRepo.markSynced(entry.entityId);
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
      if (entry.entityType === "medication") {
        await medicationsRepo.markError(entry.entityId, error);
      } else {
        await doseLogsRepo.markError(entry.entityId, error);
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
