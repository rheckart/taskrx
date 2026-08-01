import { useUser } from "@clerk/clerk-expo";
import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api } from "@taskrx/convex";
import type {
  DoseLog,
  Medication,
  Reminder,
  ReminderInstance,
} from "@taskrx/shared";
import { useQuery } from "convex/react";
import { useEffect, useMemo, useState } from "react";

import * as doseLogsRepo from "../db/repos/doseLogs";
import * as medicationsRepo from "../db/repos/medications";
import * as instancesRepo from "../db/repos/reminderInstances";
import * as remindersRepo from "../db/repos/reminders";
import * as syncQueueRepo from "../db/repos/syncQueue";

import { syncWorker } from "./worker";

/**
 * Pull sync hooks.
 *
 * These hooks subscribe to Convex realtime queries and merge remote changes
 * into the local SQLite database in the background. Screens read from SQLite
 * directly — `remindersRepo.list` and `instancesRepo.list` — for instant
 * offline-first rendering; these hooks only keep that store current.
 *
 * `useUnifiedSync` is the live one, and it is deliberately a single hook
 * covering both unified tables — an instance cannot be applied before its
 * parent reminder, and that ordering does not survive being split across two
 * effects. See its own comment.
 *
 * `useMedicationsSync` and `useDoseLogsSync` below are legacy and have had no
 * callers since the sync cutover; `useUnifiedSync` explains why running them
 * alongside it would be actively harmful rather than merely redundant. They are
 * retained because the cutover tickets delete nothing, and go with the rest of
 * the legacy generation.
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

/**
 * The unified pull path — the return leg of the cutover.
 *
 * #6 moved the device's writes onto `reminders` and `reminder_instances`, and
 * the worker already pushes them, but nothing read them back: a reminder
 * created here reached Convex and could not return to a second device or
 * survive a reinstall. These two hooks close that.
 *
 * They are separate subscriptions rather than one, because Convex delivers them
 * independently and an instance names its parent by document id. The instance
 * hook therefore resolves that id locally and *skips* rows whose parent has not
 * arrived yet, exactly as `useDoseLogsSync` does — never inventing a parent and
 * never storing a Convex id in a column every other producer fills with a local
 * UUID. A skip is transient: the next delivery of either subscription retries
 * it, and `remindersRepo.upsertMany` writes `convex_id` on the way in
 * specifically so the resolution can succeed on a device that has only ever
 * received the reminder.
 */

interface ConvexReminderDoc {
  readonly _id: string;
  readonly localId: string;
  readonly userId: string;
  readonly kind: Reminder["kind"];
  readonly name: string;
  readonly rxcui?: string;
  readonly dosage?: string;
  readonly instructions?: string;
  readonly schedule: Reminder["schedule"];
  readonly priority: Reminder["priority"];
  readonly tags: readonly string[];
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly caregiverIds: readonly string[];
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
}

interface ConvexReminderInstanceDoc {
  readonly _id: string;
  readonly localId: string;
  readonly userId: string;
  readonly reminderId: string;
  readonly reminderKind: ReminderInstance["reminderKind"];
  readonly scheduledTime: number;
  readonly status: ReminderInstance["status"];
  readonly takenAt?: number;
  readonly snoozedUntil?: number;
  readonly snoozeCount: number;
  readonly notes?: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}

function convexDocToReminder(
  doc: ConvexReminderDoc
): remindersRepo.PulledReminder {
  return {
    convexId: doc._id,
    rxcui: doc.rxcui ?? null,
    reminder: {
      // `localId` is the device-generated UUID and the primary key everything
      // local joins on; `_id` is the server's handle and belongs in `convex_id`.
      id: doc.localId,
      userId: doc.userId,
      kind: doc.kind,
      name: doc.name,
      dosage: doc.dosage,
      instructions: doc.instructions,
      schedule: doc.schedule,
      priority: doc.priority,
      tags: doc.tags,
      pillsRemaining: doc.pillsRemaining,
      pillsPerDose: doc.pillsPerDose,
      refillReminderDays: doc.refillReminderDays,
      caregiverIds: doc.caregiverIds,
      active: doc.active,
      createdAt: doc.createdAt,
      updatedAt: doc.updatedAt,
    },
  };
}

function convexDocToInstance(
  doc: ConvexReminderInstanceDoc,
  localReminderId: string
): instancesRepo.PulledReminderInstance {
  return {
    convexId: doc._id,
    instance: {
      id: doc.localId,
      // The LOCAL id the caller resolved, never `doc.reminderId`. That one is
      // an `Id<"reminders">`, and `reminder_instances.reminder_id` is a foreign
      // key every other producer fills with a local UUID.
      reminderId: localReminderId,
      userId: doc.userId,
      reminderKind: doc.reminderKind,
      scheduledTime: doc.scheduledTime,
      status: doc.status,
      takenAt: doc.takenAt,
      snoozedUntil: doc.snoozedUntil,
      snoozeCount: doc.snoozeCount,
      notes: doc.notes,
      createdAt: doc.createdAt,
      updatedAt: doc.updatedAt,
    },
  };
}

/**
 * The unified pull: both subscriptions, one ordered effect.
 *
 * ## Why one hook and not two
 *
 * An instance names its parent by Convex document id, so applying one means
 * looking the parent up in the local `reminders` table. That ordering
 * requirement is what forces the two halves into a single effect, and splitting
 * them — which this started as — breaks it twice over:
 *
 *   * **The race.** Two effects run in the same commit. The reminders effect
 *     starts an unawaited `upsertMany`; the instances effect immediately calls
 *     `localIdForConvexId`. On a fresh install the lookup runs against a table
 *     the write has not reached yet, so *every* instance resolves to `null` and
 *     skips — the whole history, on exactly the second-device case this ticket
 *     exists to make work.
 *   * **The stranding.** A skip is only harmless if something retries it. With
 *     separate effects the instances effect depends on the instances query
 *     alone, so when the reminders subscription later delivers the parent, the
 *     instances result is referentially unchanged and the effect does not
 *     re-run. The skipped rows sit there until the screen remounts. An earlier
 *     version of this comment claimed "the next delivery of either subscription
 *     applies it"; that was true of neither.
 *
 * Awaiting the reminder write before resolving parents fixes the first, and
 * depending on both query results fixes the second: any delivery of either
 * subscription re-runs the whole ordered pass.
 *
 * Re-running it is cheap and idempotent. `shouldApplyRemoteReminder` rejects a
 * row whose local copy is already at the same `updatedAt`, so after the first
 * pass the reminder half writes nothing and costs one indexed read per row.
 *
 * ## Why the legacy hooks are not called alongside
 *
 * `useMedicationsSync` / `useDoseLogsSync` are deliberately left uncalled. Both
 * pulls land on the same `reminders` row for any medication created during the
 * dual-write era — same primary key by construction — but the legacy path
 * arrives via `mirrorMedicationRow`, which applies no conflict rule and
 * rewrites `active` unconditionally. Running both would let a projection of a
 * stale legacy medication clobber a reminder the unified pull had just resolved
 * correctly, which is precisely the class of defect this ticket exists to
 * avoid. No data requires the legacy path: the spec records that no released
 * build exists and no user data is at stake, and pre-cutover rows already on
 * this device still reach the unified tables through the two mirrors' repair
 * passes. The legacy hooks go with the rest of the legacy generation.
 *
 * Returns nothing. Screens read the local store rather than the subscription —
 * that is the whole offline-first arrangement — so a `data` projection here
 * would have no consumer, and building one would mean publishing an instance
 * whose `reminderId` is still a Convex document id.
 */
export function useUnifiedSync(userId: string | undefined): void {
  const remoteReminders = useQuery(
    api.reminders.list,
    userId === undefined ? "skip" : {}
  );
  const remoteInstances = useQuery(
    api.reminderInstances.list,
    userId === undefined ? "skip" : {}
  );

  useEffect(() => {
    if (
      userId === undefined ||
      remoteReminders === undefined ||
      remoteInstances === undefined
    ) {
      return;
    }

    void (async (): Promise<void> => {
      // Parents first, and awaited: everything below reads the table this
      // writes.
      await remindersRepo.upsertMany(remoteReminders.map(convexDocToReminder));

      const resolved: instancesRepo.PulledReminderInstance[] = [];
      for (const doc of remoteInstances) {
        const localReminderId = await remindersRepo.localIdForConvexId(
          doc.reminderId
        );
        if (localReminderId === null) {
          // Reachable even after the awaited write above: the parent may be
          // soft-deleted, which `api.reminders.list` filters out, so no amount
          // of waiting produces it. Skipping is the honest outcome — there is
          // no local UUID to write, and both alternatives (inventing one, or
          // storing the Convex id) corrupt a foreign key. The row is not lost;
          // it stays on the server, and any later delivery of either
          // subscription re-runs this pass.
          console.warn(
            `[sync] instance ${doc.localId} skipped: reminder ${doc.reminderId} not pulled yet`
          );
          continue;
        }
        resolved.push(convexDocToInstance(doc, localReminderId));
      }
      await instancesRepo.upsertMany(resolved);
    })().catch((error: unknown) => {
      console.error("[sync] unified pull failed:", error);
    });
  }, [remoteReminders, remoteInstances, userId]);
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
