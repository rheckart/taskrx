import { useUser } from "@clerk/clerk-expo";
import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api } from "@taskrx/convex";
import type { Reminder, ReminderInstance } from "@taskrx/shared";
import { useQuery } from "convex/react";
import { useEffect, useMemo, useState } from "react";

import * as instancesRepo from "../db/repos/reminderInstances";
import * as remindersRepo from "../db/repos/reminders";
import * as syncQueueRepo from "../db/repos/syncQueue";

import { syncWorker } from "./worker";

/**
 * Pull sync.
 *
 * `useUnifiedSync` subscribes to the Convex realtime queries and merges remote
 * changes into the local SQLite database in the background. Screens read from
 * SQLite directly — `remindersRepo.list` and `instancesRepo.list` — for instant
 * offline-first rendering; this only keeps that store current.
 *
 * It is deliberately ONE hook covering both unified tables, because an instance
 * cannot be applied before its parent reminder and that ordering does not
 * survive being split across two effects. See its own comment.
 *
 * A legacy `useMedicationsSync` / `useDoseLogsSync` pair used to sit alongside
 * it, pulling `medications` and `dose_logs`; both went with the legacy
 * generation.
 */

export type UISyncStatus =
  | "synced"
  | "pending"
  | "syncing"
  | "offline"
  | "error";

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
