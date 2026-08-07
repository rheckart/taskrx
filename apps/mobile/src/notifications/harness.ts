import {
  createReminder,
  dailyTimes,
  onceTimes,
  weeklyTimes,
} from "@taskrx/shared";
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

import {
  checkExactAlarmPermission,
  checkNotificationPermission,
} from "./permissions";

import {
  MAX_GLOBAL_TRIGGERS,
  MAX_TASK_TRIGGERS,
  MEDICATION_REMINDER_CHANNEL_ID,
  armReminders,
  cancelAll,
  createNotificationChannels,
  rescheduleAll,
  scheduleForReminder,
  taskIdPrefixFor,
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
 * - **Local-only** (`schedule` … `notification-status`) drive notifee with
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
  | "schedule-task"
  | "schedule-task-flood"
  | "cancel-all"
  | "pending-count"
  | "pending-ids"
  | "exact-alarm-status"
  | "notification-status"
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
  "schedule-task",
  "schedule-task-flood",
  "cancel-all",
  "pending-count",
  "pending-ids",
  "exact-alarm-status",
  "notification-status",
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
  await createNotificationChannels();
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
  await createNotificationChannels();
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
 * The pending trigger ids under `prefix` whose remainder matches `rest`.
 *
 * A `startsWith` alone is not enough to COUNT with, and the difference is not
 * academic: a reminder id of `t-1-other` produces `task-t-1-other-…`, which
 * starts with `task-t-1-`. The medication namespace has the same property and
 * documents it as a known limitation, because there nothing turns on it — here
 * a verdict does. Case 11 concludes "the task budget is full" from a count, and
 * a count inflated by ids belonging to something else would report a case as
 * proven when it had proven nothing.
 */
function armedUnder(
  ids: readonly string[],
  prefix: string,
  rest: RegExp
): readonly string[] {
  return ids.filter(
    (id) => id.startsWith(prefix) && rest.test(id.slice(prefix.length))
  );
}

/**
 * Arms one task through the production path, for the task-Tier delivery case.
 *
 * `armReminders` rather than a direct notifee write, for the same reason
 * `seed-due-reminder` uses `rescheduleAll`: what is under test is the app's own
 * choice of trigger, and a harness that spelled the trigger out itself would
 * verify the harness. It reports the armed ids so the shell can check that they
 * are in the task id namespace, and the fire time so it knows when to look for
 * the notification.
 *
 * A `once` schedule, not `daily`: one task, one moment, nothing left armed
 * afterwards to confuse the next case.
 */
async function runScheduleTask(spec: HarnessSpec): Promise<string> {
  await createNotificationChannels();
  const now = Date.now();
  const fireAt = now + spec.delayMs;
  const reminder = createReminder({
    id: spec.id,
    userId: "harness",
    kind: "task",
    name: `${HARNESS_REMINDER_MARKER} ${spec.id}`,
    schedule: onceTimes(fireAt),
    createdAt: now,
    updatedAt: now,
  });

  await armReminders([reminder]);

  const armed = armedUnder(
    await notifee.getTriggerNotificationIds(),
    taskIdPrefixFor(spec.id),
    /^\d+$/
  );
  return (
    `task_armed=${String(armed.length)} ` +
    `task_ids=${armed.join(",")} ` +
    `fire_at=${String(fireAt)}`
  );
}

/**
 * How many tasks the non-displacement case arms alongside one medication.
 *
 * It has to clear **both** budgets, and the second one is the one that makes
 * the case mean anything:
 *
 *   * More than `MAX_TASK_TRIGGERS`, so the task Tier fills completely. Until
 *     it does, "the dose is still armed" says nothing — there was room for
 *     everybody.
 *   * More than `MAX_GLOBAL_TRIGGERS`, because that is the budget a Kind-blind
 *     scheduler would be ranking both Kinds inside. With fewer tasks than 64
 *     such a scheduler arms all of them AND the dose, so the failure this case
 *     exists to catch cannot occur and the case passes a broken build.
 *
 * The first version of this got the second condition wrong and the fixture
 * wrong with it, which is recorded here rather than quietly corrected: 40
 * `daily` tasks at 00:05–00:20, against a dose ten minutes out. Every one of
 * those tasks was *later* than the dose, not sooner, so a purely time-ordered
 * Kind-blind selector would have ranked the dose first and armed it. Both
 * review axes caught it independently.
 */
const FLOOD_TASK_COUNT = 80;

/**
 * Arms a flood of tasks and one medication together, for the non-displacement
 * case.
 *
 * **Every task fires before the dose, and that is the whole fixture.** A
 * Kind-blind scheduler orders purely by time — which is what this app did
 * before ADR 0003 — so with 80 task occurrences in the next few minutes and one
 * dose ten minutes out, it fills its 64 slots with tasks and the dose is never
 * armed at all. That is the failure this case exists to catch, and on a real
 * device it is silent: no error, no warning, the alarm simply is not there.
 * Tasks scheduled *after* the dose would let that same scheduler rank the dose
 * first and pass.
 *
 * `once` rather than `daily` for exactly that reason. A daily schedule's next
 * occurrence depends on the device's wall clock, so "sooner than the dose"
 * would hold or not hold depending on what time the case happened to run — and
 * an unfalsifiable case that passes at 3pm is worse than no case.
 *
 * Reports both counts, both budgets and the dose's own ids, so the shell can
 * assert the case's own premises before it reads a verdict out of them: that
 * the task budget really did fill, that the flood was large enough to have
 * displaced the dose had the Tiers shared a budget, and that the dose is still
 * armed. Any of those alone would pass something broken — a scheduler that
 * armed no tasks at all satisfies the last.
 */
async function runScheduleTaskFlood(spec: HarnessSpec): Promise<string> {
  await createNotificationChannels();
  const now = Date.now();
  const doseAt = now + spec.delayMs;

  const reminders = [
    createReminder({
      id: `${spec.id}-dose`,
      userId: "harness",
      kind: "medication",
      name: `${HARNESS_REMINDER_MARKER} ${spec.id} dose`,
      dosage: HARNESS_REMINDER_DOSAGE,
      schedule: onceTimes(doseAt),
      createdAt: now,
      updatedAt: now,
    }),
    ...Array.from({ length: FLOOD_TASK_COUNT }, (_, index) =>
      createReminder({
        id: `${spec.id}-task-${String(index)}`,
        userId: "harness",
        kind: "task",
        name: `${HARNESS_REMINDER_MARKER} ${spec.id} task ${String(index)}`,
        // A minute out and one second apart, so every one of them is sooner
        // than the dose and no two share an instant — the ordering the case
        // depends on is then a fact about the fixture rather than about the
        // tie-break.
        schedule: onceTimes(now + 60_000 + index * 1_000),
        createdAt: now,
        updatedAt: now,
      })
    ),
  ];

  await armReminders(reminders);

  const pending = await notifee.getTriggerNotificationIds();
  const doseIds = armedUnder(pending, `${spec.id}-dose-`, /^\d+$/);
  const taskIds = armedUnder(
    pending,
    `${taskIdPrefixFor(spec.id)}task-`,
    // `<index>-<fireAt>`: the flood's own reminder ids carry the index.
    /^\d+-\d+$/
  );

  // Both budgets are reported rather than left for the shell to know, so the
  // case can check its own premises without a second copy of either number
  // going stale the day one of them moves. `flood` against `dose_budget` is
  // the premise that a Kind-blind scheduler would have dropped the dose here.
  return (
    `dose_armed=${String(doseIds.length)} ` +
    `task_armed=${String(taskIds.length)} ` +
    `task_budget=${String(MAX_TASK_TRIGGERS)} ` +
    `dose_budget=${String(MAX_GLOBAL_TRIGGERS)} ` +
    `flood=${String(FLOOD_TASK_COUNT)} ` +
    `dose_ids=${doseIds.join(",")} ` +
    `dose_at=${String(doseAt)}`
  );
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
  await createNotificationChannels();

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
    case "schedule-task":
      return runScheduleTask(spec);
    case "schedule-task-flood":
      return runScheduleTaskFlood(spec);
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
    /**
     * The app's OWN reading of whether it may post a notification.
     *
     * Reported so a device case can hold it against `dumpsys package`, which
     * reads the same permission from the other side. Agreement across a change
     * is the only external evidence that this is a real platform query rather
     * than the kind of hardcoded boolean the POC shipped for exact alarms — a
     * constant agrees with the OS in one state and cannot follow it into the
     * other. Deliberately does NOT prompt: a status read that changed the state
     * it reports would be useless to case 12, which is measuring what the app
     * believed before anybody was asked.
     */
    case "notification-status": {
      const permission = await checkNotificationPermission();
      return (
        `notification_status=${permission.status} ` +
        `notifications_granted=${String(permission.notificationGranted)} ` +
        `exact_alarm_granted=${String(permission.exactAlarmGranted)}`
      );
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
