import { useUser } from "@clerk/clerk-expo";
import NetInfo, { type NetInfoState } from "@react-native-community/netinfo";
import { api } from "@taskrx/convex";
import type { Reminder, ReminderInstance } from "@taskrx/shared";
import {
  shouldApplyRemoteReminder,
  shouldApplyRemoteReminderInstance,
} from "@taskrx/shared";
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
  readonly completedAt?: number;
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
      completedAt: doc.completedAt,
      snoozedUntil: doc.snoozedUntil,
      snoozeCount: doc.snoozeCount,
      notes: doc.notes,
      createdAt: doc.createdAt,
      updatedAt: doc.updatedAt,
    },
  };
}

/**
 * The ordered merge pass, extracted from the effect below so it can be run by
 * a test rather than only reasoned about.
 *
 * Everything in the effect's comment about ordering is a property of THIS
 * function: parents are written and awaited before any instance resolves its
 * parent, and an instance whose parent is absent is skipped rather than written
 * with a foreign key it cannot honour. `src/sync/pull.test.ts` executes both.
 *
 * ## Why the conflict rules are decided here and not in the repositories
 *
 * Both `shouldApply…` guards used to live inside the two `upsertMany`
 * implementations, one row at a time. That put the safety-critical rule — "a
 * locally completed Instance is never overwritten by a stale remote copy" —
 * behind `getDb()`, and so behind expo-sqlite, which does not load in a node
 * test. The rule was executed as a pure function by
 * `packages/shared/src/sync/conflict.test.ts` and the surrounding pass was
 * executed by `pull.test.ts`, but nothing ran the two together: it was proven of
 * the decision and of the pass, never of the pair. That gap is what issue #7
 * left unticked.
 *
 * Deciding here closes it without inventing a database test seam, which
 * `docs/specs/fused-timeline.md` forbids — and it is what that spec asks for
 * anyway: "the on-device repositories persist what these functions decide. They
 * must not contain the decisions themselves". The repositories are now exactly
 * that, and their `upsertMany` writes every row it is handed.
 *
 * It is exported for that reason alone; `useUnifiedSync` is its only production
 * caller, and callers that want the subscriptions should use the hook.
 */
export async function applyRemotePull(
  remoteReminders: readonly ConvexReminderDoc[],
  remoteInstances: readonly ConvexReminderInstanceDoc[]
): Promise<void> {
  const applicableReminders: remindersRepo.PulledReminder[] = [];
  for (const doc of remoteReminders) {
    const candidate = convexDocToReminder(doc);
    const local = await remindersRepo.get(candidate.reminder.id);
    if (!shouldApplyRemoteReminder(local, candidate.reminder)) {
      continue;
    }
    applicableReminders.push(candidate);
  }
  // Parents first, and awaited: everything below reads the table this writes.
  await remindersRepo.upsertMany(applicableReminders);

  const resolved: instancesRepo.PulledReminderInstance[] = [];
  for (const doc of remoteInstances) {
    const localReminderId = await remindersRepo.localIdForConvexId(
      doc.reminderId
    );
    if (localReminderId === null) {
      // Reachable even after the awaited write above: the parent may be
      // soft-deleted, which `api.reminders.list` filters out, so no amount of
      // waiting produces it. Skipping is the honest outcome — there is no local
      // UUID to write, and both alternatives (inventing one, or storing the
      // Convex id) corrupt a foreign key. The row is not lost; it stays on the
      // server, and any later delivery of either subscription re-runs this pass.
      console.warn(
        `[sync] instance ${doc.localId} skipped: reminder ${doc.reminderId} not pulled yet`
      );
      continue;
    }

    const candidate = convexDocToInstance(doc, localReminderId);
    const local = await instancesRepo.get(candidate.instance.id);
    if (!shouldApplyRemoteReminderInstance(local, candidate.instance)) {
      // The safety-critical skip: a dose this device recorded as taken is never
      // replaced by a copy from another one. Unlike the parent-missing skip
      // above there is nothing to retry — the local row is the answer.
      continue;
    }
    resolved.push(candidate);
  }
  await instancesRepo.upsertMany(resolved);
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
 * `applyRemotePull` awaiting the reminder write before resolving parents fixes
 * the first; depending on both query results here fixes the second, since any
 * delivery of either subscription re-runs the whole ordered pass.
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

    void applyRemotePull(remoteReminders, remoteInstances).catch(
      (error: unknown) => {
        console.error("[sync] unified pull failed:", error);
      }
    );
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
