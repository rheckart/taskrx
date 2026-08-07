import { createReminder, dailyTimes, weeklyTimes } from "@taskrx/shared";
import notifee, {
  AlarmType,
  AndroidImportance,
  TriggerType,
  type TimestampTrigger,
} from "react-native-notify-kit";

import { getDb } from "../db";
import * as instancesRepo from "../db/repos/reminderInstances";
import * as remindersRepo from "../db/repos/reminders";
import * as syncQueueRepo from "../db/repos/syncQueue";
import { syncWorker } from "../sync/worker";

import { checkExactAlarmPermission } from "./permissions";

import {
  MEDICATION_REMINDER_CHANNEL_ID,
  cancelAll,
  createNotificationChannel,
  rescheduleAll,
  scheduleForReminder,
} from "./index";

/**
 * Action layer for the adb-driven notification harness.
 *
 * Parameters arrive as PATH SEGMENTS rather than a query string because
 * expo-router does not match `taskrx://harness?a=b` deep links on a custom
 * scheme — only the path form routes reliably. See
 * `apps/mobile/scripts/notification-harness.sh`.
 *
 * The actions split into two families, and the split is not cosmetic:
 *
 * - **Local-only** (`schedule` … `exact-alarm-status`) drive notifee with
 *   in-memory data and run on a fresh, signed-out install. Harness cases 1-7
 *   use these, which is why that suite can be the delivery baseline on any
 *   device.
 * - **Signed-in** (`auth-status` … `cleanup-seeded`) read and write the real
 *   SQLite tables as the authenticated user, and back harness cases 8 and 9.
 *   They need a Clerk subject, so every one of them refuses — reporting
 *   `signed_in=false` and writing nothing — rather than seeding rows under a
 *   guessed id. `HarnessScreen` supplies the subject; see `HarnessContext`.
 */

export type HarnessAction =
  | "schedule"
  | "schedule-weekly"
  | "cancel-all"
  | "pending-count"
  | "pending-ids"
  | "exact-alarm-status"
  | "auth-status"
  | "seed-due-reminder"
  | "instance-at"
  | "queue-count"
  | "sync-status"
  | "cleanup-seeded"
  | "purge-seeded";

export interface HarnessSpec {
  readonly action: HarnessAction;
  readonly id: string;
  /**
   * The third path segment read as a RELATIVE delay, floored at one second so
   * a scheduling action cannot arm an alarm in the past.
   */
  readonly delayMs: number;
  /**
   * The same segment unclamped. `instance-at` reads it as an absolute epoch,
   * where the floor above would be meaningless rather than merely unused.
   */
  readonly value: number;
  readonly nonce: string;
}

/**
 * Who the app is signed in as, threaded from `HarnessScreen`.
 *
 * A separate argument rather than a lookup inside this module because the
 * Clerk subject is only reachable through a React hook, and this file has to
 * stay importable by the local-only tree — where there is no ClerkProvider at
 * all and calling that hook throws.
 */
export interface HarnessContext {
  readonly userId: string | null;
}

/**
 * Name prefix for reminders this harness creates, and the only thing
 * `cleanup-seeded` matches on. It has to be recognisable in the user's own
 * medication list, because a seeded row that outlives its case shows up there
 * beside real prescriptions.
 */
export const HARNESS_REMINDER_MARKER = "[harness]";

/** Rendered by `/dose/log` under the name, so a case can assert both. */
const HARNESS_REMINDER_DOSAGE = "1 tablet";

const VALID_ACTIONS: readonly HarnessAction[] = [
  "schedule",
  "schedule-weekly",
  "cancel-all",
  "pending-count",
  "pending-ids",
  "exact-alarm-status",
  "auth-status",
  "seed-due-reminder",
  "instance-at",
  "queue-count",
  "sync-status",
  "cleanup-seeded",
  "purge-seeded",
];

function isHarnessAction(value: string): value is HarnessAction {
  return (VALID_ACTIONS as readonly string[]).includes(value);
}

/**
 * Parses `[action, id, delayMs, nonce]` path segments, tolerating any
 * missing tail segments.
 */
export function parseHarnessSpec(segments: readonly string[]): HarnessSpec {
  const rawAction = segments[0] ?? "pending-count";
  const action: HarnessAction = isHarnessAction(rawAction)
    ? rawAction
    : "pending-count";
  const id = segments[1] ?? `harness-${String(Date.now())}`;
  const parsedDelay = Number.parseInt(segments[2] ?? "90000", 10);
  const value = Number.isNaN(parsedDelay) ? 90000 : parsedDelay;
  const delayMs = Math.max(1000, value);
  const nonce = segments[3] ?? "";
  return { action, id, delayMs, value, nonce };
}

async function runSchedule(spec: HarnessSpec): Promise<string> {
  await createNotificationChannel();
  const scheduledTime = Date.now() + spec.delayMs;
  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: { type: AlarmType.SET_ALARM_CLOCK },
  };
  await notifee.createTriggerNotification(
    {
      id: spec.id,
      title: `Harness ${spec.id}`,
      body: "Scheduled by the notification harness",
      data: {
        medicationId: spec.id,
        scheduledTime: String(scheduledTime),
        harness: "true",
      },
      android: {
        channelId: MEDICATION_REMINDER_CHANNEL_ID,
        smallIcon: "ic_launcher",
        // Not renamed with ADR 0001 — see `scheduler.ts` for why this string
        // is frozen.
        pressAction: { id: "mark-taken", launchActivity: "default" },
        importance: AndroidImportance.HIGH,
        autoCancel: true,
      },
    },
    trigger
  );
  return `scheduled id=${spec.id} at=${String(scheduledTime)} delayMs=${String(spec.delayMs)}`;
}

/**
 * Weekday and time-of-day the weekly harness case asserts against. Mon/Wed/Fri
 * is chosen because it is non-contiguous — a scheduler that ignored
 * `daysOfWeek` and fell back to daily would produce consecutive days and fail
 * the check, whereas a contiguous set could pass by accident.
 */
const WEEKLY_DAYS = [1, 3, 5] as const;
const WEEKLY_TIME = "09:00";
const WEEKLY_COUNT = 3;

/**
 * Schedules a genuinely weekly reminder through the production path.
 *
 * This is the case the POC could not express at all: `medications` had no
 * shape for a weekly schedule, so `weekly` reminders produced no alarms. It
 * deliberately goes through `scheduleForReminder` rather than calling notifee
 * directly (as `runSchedule` does), because the thing under test is the
 * scheduler's use of the unified engine, not notifee's trigger API.
 *
 * Returns the fire timestamps so the shell harness can verify each one lands
 * on a requested weekday at the requested wall-clock time — a check that runs
 * against real device-local time, not the app's own assertion.
 */
async function runScheduleWeekly(spec: HarnessSpec): Promise<string> {
  await createNotificationChannel();
  const now = Date.now();
  const reminder = createReminder({
    id: spec.id,
    userId: "harness",
    kind: "medication",
    name: `Harness ${spec.id}`,
    schedule: weeklyTimes([...WEEKLY_DAYS], [WEEKLY_TIME]),
    createdAt: now,
    updatedAt: now,
  });

  await scheduleForReminder(reminder, WEEKLY_COUNT);

  const ids = await notifee.getTriggerNotificationIds();
  const fireAts = ids
    .filter((id) => id.startsWith(`${spec.id}-`))
    .map((id) => id.slice(spec.id.length + 1))
    .sort();
  return `weekly_fire_ats=${fireAts.join(",")}`;
}

/**
 * The one answer every signed-in action gives when there is no subject.
 *
 * Identical to `auth-status`'s signed-out reply on purpose: the shell harness
 * greps a single `signed_in=false` to decide that a case was never run, and
 * records it as an unevaluated case rather than a product failure. A case that
 * read a bespoke error string per action would have to keep that list in sync
 * to keep telling the truth.
 */
const SIGNED_OUT_REPLY = "signed_in=false user_id=none";

function pad2(value: number): string {
  return String(value).padStart(2, "0");
}

/**
 * The first whole local minute at or after `ms`.
 *
 * Rounds UP rather than to the nearest minute because the caller asked for a
 * lead time and the schedule engine only has minute resolution: rounding down
 * hands back an occurrence sooner than requested, and a case whose setup is
 * still running when the alarm fires reports a delivery failure that did not
 * happen.
 */
function nextWholeMinute(ms: number): number {
  return Math.ceil(ms / 60_000) * 60_000;
}

/**
 * Seeds a real reminder due shortly, then arms it through the production path.
 *
 * `rescheduleAll` rather than a direct notifee write, and the distinction is
 * the whole point of this action. In the signed-in tree `_layout.tsx` calls
 * `rescheduleAll` on every transition to `active`, and its cancel pass removes
 * every trigger outside the schedule-derived desired set — so an alarm armed
 * any other way is retracted before it can fire. Measured on a Pixel 8 Pro:
 * an alarm armed by the `schedule` action above was gone from
 * `getTriggerNotificationIds()` within one foreground, and the notification
 * never posted. See constraint 11 in `notification-harness.sh`.
 *
 * The reported `fire_at` is read back out of Notifee rather than computed from
 * the schedule, so it is the time an alarm is ACTUALLY armed for. A computed
 * time that no alarm backs would send the caller off to wait for a
 * notification that was never going to post, and then blame delivery for it.
 */
async function runSeedDueReminder(
  spec: HarnessSpec,
  userId: string
): Promise<string> {
  await createNotificationChannel();

  const target = new Date(nextWholeMinute(Date.now() + spec.delayMs));
  const hhmm = `${pad2(target.getHours())}:${pad2(target.getMinutes())}`;

  const reminder = await remindersRepo.create({
    userId,
    kind: "medication",
    name: `${HARNESS_REMINDER_MARKER} ${spec.id}`,
    dosage: HARNESS_REMINDER_DOSAGE,
    schedule: dailyTimes([hhmm]),
  });

  await rescheduleAll(userId);

  const prefix = `${reminder.id}-`;
  const fireAts = (await notifee.getTriggerNotificationIds())
    .filter((id) => id.startsWith(prefix))
    .map((id) => Number.parseInt(id.slice(prefix.length), 10))
    .filter((fireAt) => Number.isFinite(fireAt))
    .sort((a, b) => a - b);

  const soonest = fireAts[0];
  return (
    `seeded reminder_id=${reminder.id} ` +
    `fire_at=${soonest === undefined ? "none" : String(soonest)} ` +
    `hhmm=${hhmm} armed=${String(fireAts.length)}`
  );
}

/**
 * Reports the instances at one exact `(reminder, scheduled time)` pairing.
 *
 * Status-blind on purpose. `findAnswerableForOccurrence` — what `/dose/log` uses
 * to load — filters to `ANSWERABLE_STATUSES`, so it reports nothing for an
 * occurrence that has been answered, and case 9 reads this back precisely
 * because the dose was answered. What the caller is asking is "did the screen
 * land on the right occurrence", not "is it still unanswered".
 *
 * `completed` is reported separately from the first row because an answered
 * occurrence carries MORE THAN ONE instance on a real device: `/dose/log`
 * re-runs its load after the write, finds no answerable instance (the one it
 * held is now completed) and mints a fresh one. `list` orders by `scheduled_time`,
 * which is equal across all of them, so which row came first was effectively
 * arbitrary — and case 9 read the duplicate and reported a recorded dose as
 * unrecorded. Sorting by `createdAt` makes the first row the one the screen
 * minted on load, and naming the completed row removes the guess entirely.
 */
async function runInstanceAt(
  spec: HarnessSpec,
  userId: string
): Promise<string> {
  const instances = [
    ...(await instancesRepo.list({
      userId,
      reminderId: spec.id,
      since: spec.value,
      until: spec.value,
    })),
  ].sort((a, b) => a.createdAt - b.createdAt);

  const first = instances[0];
  const completed = instances.find(
    (instance) => instance.status === "completed"
  );
  const statuses = instances.map((instance) => instance.status).join(",");

  return (
    `instance_at=${first?.id ?? "none"} ` +
    `status=${first?.status ?? "none"} ` +
    `count=${String(instances.length)} ` +
    `completed=${completed?.id ?? "none"} ` +
    `statuses=${statuses === "" ? "none" : statuses}`
  );
}

/**
 * Soft-deletes every reminder this harness seeded, then re-arms what is left.
 *
 * The reschedule is not tidiness. `softDelete` drops the row out of the active
 * set but does not touch Notifee, so the seeded reminder's alarms stay pending
 * and go on firing on a device nobody is testing any more; `rescheduleAll` is
 * the pass that retracts them.
 */
async function runCleanupSeeded(userId: string): Promise<string> {
  const seeded = (await remindersRepo.list(userId)).filter((reminder) =>
    reminder.name.startsWith(HARNESS_REMINDER_MARKER)
  );
  for (const reminder of seeded) {
    await remindersRepo.softDelete(reminder.id);
  }
  await rescheduleAll(userId);
  return `cleaned=${String(seeded.length)}`;
}

/**
 * Hard-deletes everything the harness seeded: the reminders, the instances
 * behind them, and their `sync_queue` entries.
 *
 * `cleanup-seeded` above only soft-deletes, which is right for a case tidying
 * up after itself — the reminder disappears from the medication list and the
 * app keeps its history. Across many runs that history is not history: each
 * run leaves a fabricated reminder and an answered dose, and adherence is
 * computed from exactly those `reminder_instances` rows. This is the pass that
 * removes them.
 *
 * The SQL lives here rather than in `db/repos/` on purpose. The app has no hard
 * delete anywhere and should not grow one: a medication a user removed is a
 * record they may need, which is why `remindersRepo.softDelete` is a status
 * change. This is rig cleanup, so it stays in the rig.
 *
 * Delete the CONVEX rows first (`npx convex run harnessData:purgeSeeded '{}'`),
 * then run this. In the other order a pull can land between the two and write
 * the reminders straight back down.
 */
async function runPurgeSeeded(userId: string): Promise<string> {
  const db = await getDb();

  // Read every row for the user, including the inactive ones — `cleanup-seeded`
  // has usually soft-deleted these already, so `remindersRepo.list` (which
  // filters `active = 1`) cannot see the very rows this is here to remove.
  //
  // Filtered in JS with the same `startsWith` the seeding uses, rather than a
  // SQL `LIKE '[harness]%'`: it keeps the matching rule in one place and keeps
  // the marker's brackets out of a pattern language.
  const rows = await db.getAllAsync<{ id: string; name: string }>(
    "SELECT id, name FROM reminders WHERE user_id = ?",
    userId
  );
  const seededIds = rows
    .filter((row) => row.name.startsWith(HARNESS_REMINDER_MARKER))
    .map((row) => row.id);

  if (seededIds.length === 0) {
    return "purged_reminders=0 purged_instances=0 purged_queue=0";
  }

  const reminderList = seededIds.map(() => "?").join(",");
  const instances = await db.getAllAsync<{ id: string }>(
    `SELECT id FROM reminder_instances WHERE reminder_id IN (${reminderList})`,
    ...seededIds
  );
  const instanceIds = instances.map((instance) => instance.id);
  const queueIds = [...seededIds, ...instanceIds];
  const queueList = queueIds.map(() => "?").join(",");

  // Every count below is SQLite's own `changes`, never the length of the id
  // list handed to the statement. The two are not the same and the difference
  // is not academic: the queue delete is given one id per reminder AND per
  // instance, so the first run of this reported `purged_queue=29` when the
  // queue held a single row. A cleanup tool that overstates what it removed is
  // worse than one that removes nothing.

  // Queue first: an entry left behind names a row that no longer exists, and
  // the worker would spend all five attempts failing to resolve it before
  // dead-lettering — noise that looks like a sync defect.
  const queueResult = await db.runAsync(
    `DELETE FROM sync_queue WHERE entity_id IN (${queueList})`,
    ...queueIds
  );
  let instanceChanges = 0;
  if (instanceIds.length > 0) {
    const instanceResult = await db.runAsync(
      `DELETE FROM reminder_instances WHERE id IN (${instanceIds
        .map(() => "?")
        .join(",")})`,
      ...instanceIds
    );
    instanceChanges = instanceResult.changes;
  }
  // Parents last. `reminder_instances.reminder_id` is a foreign key, so the
  // other order is rejected outright rather than merely leaving orphans.
  const reminderResult = await db.runAsync(
    `DELETE FROM reminders WHERE id IN (${reminderList})`,
    ...seededIds
  );

  return (
    `purged_reminders=${String(reminderResult.changes)} ` +
    `purged_instances=${String(instanceChanges)} ` +
    `purged_queue=${String(queueResult.changes)}`
  );
}

export async function runHarnessAction(
  spec: HarnessSpec,
  context: HarnessContext
): Promise<string> {
  const { userId } = context;
  switch (spec.action) {
    case "schedule":
      return runSchedule(spec);
    case "schedule-weekly":
      return runScheduleWeekly(spec);
    case "cancel-all": {
      await cancelAll();
      return "cancelled all trigger notifications";
    }
    case "pending-count": {
      const ids = await notifee.getTriggerNotificationIds();
      return `pending_count=${String(ids.length)}`;
    }
    case "pending-ids": {
      const ids = await notifee.getTriggerNotificationIds();
      return `pending_ids=${ids.join(",")}`;
    }
    case "exact-alarm-status": {
      const granted = await checkExactAlarmPermission();
      return `exact_alarm_granted=${String(granted)}`;
    }
    case "auth-status":
      return userId === null
        ? SIGNED_OUT_REPLY
        : `signed_in=true user_id=${userId}`;
    case "seed-due-reminder":
      return userId === null
        ? SIGNED_OUT_REPLY
        : runSeedDueReminder(spec, userId);
    case "instance-at":
      return userId === null ? SIGNED_OUT_REPLY : runInstanceAt(spec, userId);
    case "queue-count": {
      // Answers signed out, unlike its siblings: `sync_queue` is not
      // user-scoped, and the depth is diagnostic rather than a write.
      //
      // The two depths stay separate because they drain differently — a
      // dead-lettered row has spent its five attempts and will never leave the
      // queue on reconnect, so folding it into `pending` would make case 9
      // wait out its whole budget for a row that cannot move.
      const pending = await syncQueueRepo.pending();
      const dead = await syncQueueRepo.deadLettered();
      return `queue_pending=${String(pending.length)} queue_dead=${String(dead.length)}`;
    }
    case "sync-status":
      return `sync_status=${syncWorker.currentStatus}`;
    case "cleanup-seeded":
      return userId === null ? SIGNED_OUT_REPLY : runCleanupSeeded(userId);
    case "purge-seeded":
      return userId === null ? SIGNED_OUT_REPLY : runPurgeSeeded(userId);
    default:
      return `unknown_action=${String(spec.action)}`;
  }
}
