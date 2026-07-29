import { useUser } from "@clerk/clerk-expo";
import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api } from "@taskrx/convex";
import type { DoseLog, Medication } from "@taskrx/shared";
import { useQuery } from "convex/react";
import { useEffect, useMemo, useState } from "react";

import * as doseLogsRepo from "../db/repos/doseLogs";
import * as medicationsRepo from "../db/repos/medications";
import * as syncQueueRepo from "../db/repos/syncQueue";

import { syncWorker } from "./worker";

/**
 * Pull sync hooks.
 *
 * These hooks subscribe to Convex realtime queries and mirror remote changes
 * into the local SQLite database in the background. UI screens should read
 * from SQLite directly (via `medicationsRepo.selectAll` / `doseLogsRepo.selectAll`)
 * for instant offline-first rendering; these hooks only keep the cache warm.
 */

export type UISyncStatus =
  | "synced"
  | "pending"
  | "syncing"
  | "offline"
  | "error";

interface UseSyncReturn<T> {
  readonly data: T | undefined;
  readonly isLoading: boolean;
  readonly error: Error | null;
}

function convexDocToMedication(doc: {
  readonly _id: string;
  readonly localId: string;
  readonly userId: string;
  readonly name: string;
  readonly rxcui?: string;
  readonly dosage?: string;
  readonly scheduleType: "daily" | "interval";
  readonly scheduleTimes: unknown;
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deletedAt?: number;
}): Medication {
  return {
    id: doc.localId,
    userId: doc.userId,
    name: doc.name,
    rxcui: doc.rxcui,
    dosage: doc.dosage,
    scheduleType: doc.scheduleType,
    scheduleTimes: doc.scheduleTimes as Medication["scheduleTimes"],
    active: doc.active,
    createdAt: doc.createdAt,
    updatedAt: doc.updatedAt,
    deletedAt: doc.deletedAt,
  };
}

function convexDocToDoseLog(doc: {
  readonly _id: string;
  readonly localId: string;
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
  readonly takenAt?: number;
  readonly status: DoseLog["status"];
  readonly notes?: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}): DoseLog {
  return {
    id: doc.localId,
    userId: doc.userId,
    medicationId: doc.medicationId,
    scheduledTime: doc.scheduledTime,
    takenAt: doc.takenAt,
    status: doc.status,
    notes: doc.notes,
    createdAt: doc.createdAt,
    updatedAt: doc.updatedAt,
  };
}

export function useMedicationsSync(
  userId: string | undefined
): UseSyncReturn<readonly Medication[]> {
  const remote = useQuery(
    api.medications.list,
    userId === undefined ? "skip" : undefined
  );

  useEffect(() => {
    if (userId === undefined || remote === undefined) {
      return;
    }

    const rows = remote.map(convexDocToMedication);
    void medicationsRepo.upsertMany(rows).catch((error: unknown) => {
      console.error("[sync] failed to mirror medications:", error);
    });
  }, [remote, userId]);

  return {
    data: useMemo(
      () =>
        remote === undefined ? undefined : remote.map(convexDocToMedication),
      [remote]
    ),
    isLoading: remote === undefined,
    error: null,
  };
}

export interface DoseLogsSyncOptions {
  readonly medicationId?: string;
  readonly status?: DoseLog["status"];
  readonly since?: number;
  readonly until?: number;
}

export function useDoseLogsSync(
  userId: string | undefined,
  options: DoseLogsSyncOptions = {}
): UseSyncReturn<readonly DoseLog[]> {
  const remote = useQuery(
    api.doseLogs.list,
    userId === undefined ? "skip" : {}
  );

  useEffect(() => {
    if (userId === undefined || remote === undefined) {
      return;
    }

    const rows = remote.map(convexDocToDoseLog).filter((log) => {
      if (options.medicationId !== undefined) {
        return log.medicationId === options.medicationId;
      }
      if (options.status !== undefined) {
        return log.status === options.status;
      }
      if (options.since !== undefined && log.scheduledTime < options.since) {
        return false;
      }
      if (options.until !== undefined && log.scheduledTime > options.until) {
        return false;
      }
      return true;
    });

    // `medicationId` arrives as an `Id<"medications">` and cannot be stored as
    // one. Every other producer of `dose_logs.medication_id` writes a local
    // UUID, and the column is a join key: the mirror's EXISTS guard matches it
    // against `reminders.id`, so a Convex id there projects no instance at
    // all, and since the read cutover an unprojected dose log is one the
    // history screen cannot show. Resolve it back before the write, never
    // after — once a poisoned row is committed nothing downstream can tell it
    // from a legitimate one.
    void (async (): Promise<void> => {
      const resolved: DoseLog[] = [];
      for (const row of rows) {
        const localMedicationId = await medicationsRepo.localIdForConvexId(
          row.medicationId
        );
        if (localMedicationId === null) {
          // The medications subscription has not delivered the parent yet.
          // Skipping is the honest outcome: there is no local UUID to write,
          // and inventing one or storing the Convex id would both corrupt the
          // column. The row is not lost — it is still on the server, and the
          // next delivery of this query (or the next app start, where the
          // local copy is absent and the conflict rule admits it) applies it
          // once the parent has landed.
          console.warn(
            `[sync] dose log ${row.id} skipped: medication ${row.medicationId} not pulled yet`
          );
          continue;
        }
        resolved.push({ ...row, medicationId: localMedicationId });
      }
      await doseLogsRepo.upsertMany(resolved);
    })().catch((error: unknown) => {
      console.error("[sync] failed to mirror dose logs:", error);
    });
  }, [remote, userId, options]);

  return {
    data: useMemo(() => {
      if (remote === undefined) {
        return undefined;
      }
      return remote.map(convexDocToDoseLog).filter((log) => {
        if (options.medicationId !== undefined) {
          return log.medicationId === options.medicationId;
        }
        if (options.status !== undefined) {
          return log.status === options.status;
        }
        if (options.since !== undefined && log.scheduledTime < options.since) {
          return false;
        }
        if (options.until !== undefined && log.scheduledTime > options.until) {
          return false;
        }
        return true;
      });
    }, [remote, options]),
    isLoading: remote === undefined,
    error: null,
  };
}

export function useSyncStatus(): {
  readonly status: UISyncStatus;
  readonly isSignedIn: boolean;
} {
  const { isSignedIn } = useUser();
  const [workerStatus, setWorkerStatus] = useState(syncWorker.currentStatus);
  const [netInfo, setNetInfo] = useState<NetInfoState | null>(null);
  const [hasPending, setHasPending] = useState(false);

  useEffect(() => {
    return syncWorker.subscribe(setWorkerStatus);
  }, []);

  useEffect(() => {
    const unsubscribe = NetInfo.addEventListener(setNetInfo);
    void NetInfo.fetch().then(setNetInfo);
    return unsubscribe;
  }, []);

  useEffect(() => {
    let cancelled = false;

    const checkPending = async (): Promise<void> => {
      const entries = await syncQueueRepo.pending();
      if (!cancelled) {
        setHasPending(entries.length > 0);
      }
    };

    void checkPending();
    const interval = setInterval(() => {
      void checkPending();
    }, 5000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [workerStatus]);

  const status: UISyncStatus = useMemo(() => {
    if (!isSignedIn) {
      return "offline";
    }
    if (netInfo?.isConnected === false) {
      return "offline";
    }
    if (workerStatus === "syncing") {
      return "syncing";
    }
    if (workerStatus === "error" || hasPending) {
      return hasPending ? "pending" : "error";
    }

    return "synced";
  }, [isSignedIn, netInfo?.isConnected, workerStatus, hasPending]);

  return { status, isSignedIn: Boolean(isSignedIn) };
}
