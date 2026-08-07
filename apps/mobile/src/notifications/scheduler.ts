import type { Reminder, ScheduledOccurrence, Tier } from "@taskrx/shared";
import {
  entersMedicationHorizon,
  nextOccurrences,
  selectMedicationHorizon,
  selectTaskHorizon,
} from "@taskrx/shared";
import notifee, {
  AlarmType,
  AndroidImportance,
  type Notification,
  type TimestampTrigger,
  TriggerType,
} from "react-native-notify-kit";

import * as remindersRepo from "../db/repos/reminders";

/**
 * The Android channel id is persisted on the device the first time the channel
 * is created, and the user's per-channel sound / importance / DND overrides are
 * keyed to it. Renaming the constant is safe; changing either *string* is not —
 * it would orphan the existing channel and silently reset every override the
 * user had set.
 */
export const MEDICATION_REMINDER_CHANNEL_ID = "medication_reminders";
export const MEDICATION_REMINDER_CHANNEL_NAME = "Medication Reminders";

/**
 * The task Tier's channel. Separate from the medication one so the two carry
 * different importance — and so the person can silence their task reminders
 * without touching their doses, which one shared channel would not let them do.
 *
 * See ADR 0007 for the whole of the task Tier's delivery choice.
 */
export const TASK_REMINDER_CHANNEL_ID = "task_reminders";
export const TASK_REMINDER_CHANNEL_NAME = "Task Reminders";

/**
 * Per-reminder default used by the debug screen when scheduling a single
 * reminder in isolation. Production paths go through `rescheduleAll`,
 * which uses the two Tier budgets instead.
 */
const DEFAULT_UPCOMING_COUNT = 10;

/**
 * iOS enforces a hard cap of 64 pending local notifications per app.
 * Android has no cap but 64 is a natural rolling horizon and keeps parity
 * with the future iOS build. Selection is global across all active reminders
 * (see `selectMedicationHorizon` in `@taskrx/shared`), not per-reminder.
 */
export const MAX_GLOBAL_TRIGGERS = 64;

/**
 * The task Tier's budget, and it is *not* a share of the 64 above.
 *
 * The two are counted separately because the OS counts them separately: a
 * medication occupies an AlarmManager exact-alarm slot, a task occupies a
 * WorkManager work request, and neither subsystem knows the other exists
 * (ADR 0007). Raising this number cannot cost a dose its alarm, which is the
 * property ADR 0003 asks for.
 *
 * 32 is not an OS limit; nothing here imposes one. It bounds the work the diff
 * pass does on every foreground and the size of notify-kit's own trigger table,
 * and 32 is roughly a day and a half of a busy person's tasks — far enough out
 * that the next foreground refills it long before the horizon runs dry. If it
 * ever proves too small the fix is to raise it and re-run harness case 11, not
 * to take slots from the medication Horizon.
 */
export const MAX_TASK_TRIGGERS = 32;

/**
 * Creates the Android notification channels, one per Tier.
 *
 * The medication channel is HIGH importance so the notification bypasses Do Not
 * Disturb and alerts the user audibly — the dose is the thing the app exists to
 * interrupt for. The task channel is DEFAULT: it makes a sound and appears in
 * the shade, and it does not push past Do Not Disturb or take over the screen.
 * "Walk the dog" is not a medical event, which is story 29.
 *
 * This must be called before scheduling any notifications. It was
 * `createNotificationChannel`, singular, while one Tier was all there was.
 */
export async function createNotificationChannels(): Promise<void> {
  await notifee.createChannel({
    id: MEDICATION_REMINDER_CHANNEL_ID,
    name: MEDICATION_REMINDER_CHANNEL_NAME,
    importance: AndroidImportance.HIGH,
    vibration: true,
    vibrationPattern: [300, 500],
  });
  await notifee.createChannel({
    id: TASK_REMINDER_CHANNEL_ID,
    name: TASK_REMINDER_CHANNEL_NAME,
    importance: AndroidImportance.DEFAULT,
    vibration: true,
    vibrationPattern: [300, 500],
  });
}

/**
 * The notification id for a scheduled occurrence.
 *
 * Deliberately still `${id}-${fireAt}` rather than the engine's
 * `occurrenceId`. Migration 005 backfills `reminders` preserving the primary
 * key, so a migrated reminder's id equals the medication id that generated the
 * alarms already pending on the device. Keeping the format byte-identical
 * means the first post-upgrade `rescheduleAll` diffs those existing alarms as
 * *desired* and leaves them armed, instead of cancelling and recreating all
 * 64 — which would open a churn window on exactly the boot where the user is
 * least able to tolerate a missed dose.
 */
export function buildNotificationId(
  reminderId: string,
  scheduledTime: number
): string {
  return `${reminderId}-${String(scheduledTime)}`;
}

/**
 * Namespace marker for snooze alarms, owned here rather than in `snooze.ts`
 * because `rescheduleAll` is the code that has to recognise one.
 *
 * A snooze is armed by the user, not derived from a schedule, so it can never
 * appear in the desired set `rescheduleAll` computes — and the cancel pass
 * below removes everything not in that set. Without this exclusion, snoozing a
 * dose and then merely backgrounding and reopening the app silently cancelled
 * the deferred alarm: no error, no status change, and the reminder simply never
 * fired. `_layout.tsx` calls `rescheduleAll` on every transition to `active`,
 * so the window between arming a snooze and losing it was one app switch wide.
 *
 * Keeping the constant next to `buildNotificationId` is the point: the two id
 * spaces have to stay disjoint, and that is easier to hold true when they are
 * declared together.
 */
export const SNOOZE_NOTIFICATION_ID_PREFIX = "snooze-";

/**
 * Namespace marker for task-Tier triggers, declared here beside the other two
 * for the reason stated above: the id spaces have to stay disjoint, and that is
 * easier to hold true when they are declared together.
 *
 * The separation is not cosmetic. `notifee.getTriggerNotificationIds()` returns
 * *every* pending trigger — AlarmManager and WorkManager alike, since both are
 * rows in the same on-device table — so the cancel pass sees task triggers when
 * it computes what is stale. Sharing one id space would make each Tier's
 * reschedule cancel the other's alarms, which for the medication Tier is the
 * exact failure ADR 0003 exists to prevent, arriving from the opposite
 * direction.
 */
export const TASK_NOTIFICATION_ID_PREFIX = "task-";

/**
 * Every id a task-Tier trigger for `reminderId` can have, as a prefix.
 *
 * One function rather than the concatenation spelled at each site, because it
 * is spelled at three — the id builder, the cancel-by-reminder pass, and the
 * harness's count — and the third reads a verdict out of it.
 */
export function taskIdPrefixFor(reminderId: string): string {
  return `${TASK_NOTIFICATION_ID_PREFIX}${reminderId}-`;
}

/** The notification id for a task-Tier occurrence. */
export function buildTaskNotificationId(
  reminderId: string,
  scheduledTime: number
): string {
  return `${taskIdPrefixFor(reminderId)}${String(scheduledTime)}`;
}

/**
 * Everything the operating system is told differently about the two Tiers, in
 * one place and keyed by Tier.
 *
 * These four fields were a `kind === "medication" ?` at each of them — five
 * such branches inside a single object literal in `snooze.ts`, and a parallel
 * pair of builders here. That is the shape where one arm eventually gets edited
 * and the other does not, and in this module the consequence of the two
 * drifting is a task on the alarm-clock primitive: precisely what ADR 0003
 * forbids and precisely how it last happened, when a migration moved every path
 * off `SET_EXACT_AND_ALLOW_WHILE_IDLE` except the snooze.
 *
 * Reading it as a table is the point. What a dose gets and what a task gets is
 * four lines each, side by side, and the difference is legible without
 * following a branch.
 */
interface TierDelivery {
  readonly channelId: string;
  readonly importance: AndroidImportance;
  /**
   * The Android press-action id. Never read back — `tap-handler.ts` matches on
   * `EventType.PRESS` — but baked into every trigger already pending on the
   * device, so the medication string is frozen for the same reason its channel
   * id is. It was not renamed with ADR 0001's `taken` -> `completed`.
   */
  readonly pressActionId: string;
  readonly alarmManager: TimestampTrigger["alarmManager"];
}

export const TIER_DELIVERY: Readonly<Record<Tier, TierDelivery>> = {
  medication: {
    channelId: MEDICATION_REMINDER_CHANNEL_ID,
    importance: AndroidImportance.HIGH,
    pressActionId: "mark-taken",
    // `SET_ALARM_CLOCK` is the strongest Android guarantee available for a
    // scheduled notification, and the mechanism empirically verified firing on
    // a physical Android 17 device via the native MAUI build (see
    // `archive/dotnet-maui:src/TaskRx.Maui/Platforms/Android/Notifications/AndroidNotificationScheduler.cs:55-63`).
    // `README.md` states the invariant unqualified: do not revert it.
    //
    // Why not the weaker exact-alarm-with-doze-bypass primitive (the pre-todo-7
    // default):
    //   * `SET_ALARM_CLOCK` is exact + wakes + Doze-bypassing PLUS
    //     OEM-resilient (vendor aggressive-kill policies — Xiaomi MIUI, Oppo
    //     ColorOS, Huawei EMUI, Vivo FuntouchOS — generally respect
    //     setAlarmClock because it is the same mechanism the stock Clock app
    //     uses).
    //   * The weaker primitive has NO OEM resilience — it is the reason
    //     upstream Notifee #961 documented "SET_EXACT doesn't work in Doze".
    //
    // Deliberate tradeoff: it renders a persistent status-bar alarm-clock glyph
    // while any trigger is pending, and with a 64-occurrence rolling horizon
    // there is always one, so the icon is effectively permanent. Acceptable and
    // arguably a feature for a medication reminder — it signals that reminders
    // are armed, which is exactly what the app claims. It is neither of those
    // things for "walk the dog", which is half of why the Tiers split.
    //
    // Degradation: if `SCHEDULE_EXACT_ALARM` is not granted, notify-kit falls
    // back to `setAndAllowWhileIdle` (inexact) rather than dropping the
    // trigger. Timing may drift by minutes; delivery survives.
    alarmManager: { type: AlarmType.SET_ALARM_CLOCK },
  },
  task: {
    channelId: TASK_REMINDER_CHANNEL_ID,
    importance: AndroidImportance.DEFAULT,
    pressActionId: "answer-task",
    // **Load-bearing, and it must be written.** Omitting the key does not mean
    // "no alarm manager": notify-kit's validator defaults a timestamp trigger
    // *to* AlarmManager with `SET_EXACT_AND_ALLOW_WHILE_IDLE`, so a task
    // without this line takes an exact, Doze-bypassing alarm on the exact-alarm
    // budget — quietly, with no error. The explicit `false` is what makes the
    // validator drop the key from the bundle, which is what the native side
    // reads as "use WorkManager" (`TimestampTriggerModel` decides on
    // `containsKey`, not on the value). See ADR 0007.
    alarmManager: false,
  },
};

/** The trigger for one occurrence on `tier`. */
function buildTrigger(tier: Tier, scheduledTime: number): TimestampTrigger {
  return {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: TIER_DELIVERY[tier].alarmManager,
  };
}

/** The Android block for one notification on `tier`. */
export function androidFor(tier: Tier): NonNullable<Notification["android"]> {
  const delivery = TIER_DELIVERY[tier];
  return {
    channelId: delivery.channelId,
    smallIcon: "ic_launcher",
    pressAction: {
      id: delivery.pressActionId,
      launchActivity: "default",
    },
    importance: delivery.importance,
    autoCancel: true,
  };
}

function buildNotification(occurrence: ScheduledOccurrence): Notification {
  const dosageLabel =
    occurrence.dosage !== undefined ? ` (${occurrence.dosage})` : "";
  return {
    id: buildNotificationId(occurrence.reminderId, occurrence.fireAt),
    title: occurrence.name,
    body: `Time to take${dosageLabel}`,
    data: {
      reminderId: occurrence.reminderId,
      // Compatibility key. Alarms scheduled before the unified cutover carry
      // only `medicationId`, and `tap-handler` / `snooze` / the `/dose/log`
      // route still speak that name. Both keys hold the same value because
      // migration 005 preserves the primary key. Drop this once the read
      // screens move off `medications`.
      medicationId: occurrence.reminderId,
      scheduledTime: String(occurrence.fireAt),
    },
    android: androidFor("medication"),
  };
}

/**
 * The task-Tier notification: same moment, same one-tap answer screen, none of
 * the medical-event treatment.
 *
 * DEFAULT importance on the task channel is the visible difference — it makes a
 * sound and sits in the shade rather than bypassing Do Not Disturb or pushing a
 * heads-up over whatever the person is doing (story 29).
 *
 * `medicationId` is deliberately absent from the payload. It is a compatibility
 * key for alarms armed before the unified cutover, and no task alarm has ever
 * existed to carry it; `tap-handler.ts` prefers `reminderId` and only falls back
 * to the old name, so a task tap resolves through the same path a dose does and
 * lands on the same answer screen, which already speaks both Kinds.
 */
function buildTaskNotification(occurrence: ScheduledOccurrence): Notification {
  return {
    id: buildTaskNotificationId(occurrence.reminderId, occurrence.fireAt),
    title: occurrence.name,
    body: "Time to do it",
    data: {
      reminderId: occurrence.reminderId,
      scheduledTime: String(occurrence.fireAt),
    },
    android: androidFor("task"),
  };
}

/**
 * Schedules up to `count` upcoming notifications for a single reminder.
 *
 * Intended for the debug screen (test-one-reminder scenarios). Production
 * paths should call `rescheduleAll`, which uses the global 64-occurrence
 * horizon.
 *
 * Which Tier's alarm it arms is decided here, from the reminder's Kind, rather
 * than by whoever calls it — the guarantee belongs to whatever arms the alarm.
 * A task takes the ordinary trigger on the task channel; only a medication ever
 * reaches `SET_ALARM_CLOCK`, which is ADR 0003's rule and not this caller's
 * choice to make.
 */
export async function scheduleForReminder(
  reminder: Reminder,
  count = DEFAULT_UPCOMING_COUNT
): Promise<void> {
  if (!reminder.active || count <= 0) {
    return;
  }

  const occurrences = nextOccurrences(reminder, Date.now(), count);

  for (const occurrence of occurrences) {
    if (entersMedicationHorizon(reminder)) {
      await notifee.createTriggerNotification(
        buildNotification(occurrence),
        buildTrigger("medication", occurrence.fireAt)
      );
    } else {
      await notifee.createTriggerNotification(
        buildTaskNotification(occurrence),
        buildTrigger("task", occurrence.fireAt)
      );
    }
  }
}

/**
 * Cancels all scheduled notifications that belong to a given reminder.
 *
 * Matching is by the deterministic id prefixes, and there are two of them: a
 * medication's alarms are `${reminderId}-…` and a task's triggers are
 * `task-${reminderId}-…`. Both are checked rather than the reminder's Kind
 * being looked up, because this is the path a *deleted* reminder is cancelled
 * from — the row may already be gone, and an unknown Kind must still leave
 * nothing armed.
 */
export async function cancelForReminder(reminderId: string): Promise<void> {
  const ids = await notifee.getTriggerNotificationIds();
  const toCancel = ids.filter(
    (id) =>
      id.startsWith(`${reminderId}-`) ||
      id.startsWith(taskIdPrefixFor(reminderId))
  );
  if (toCancel.length > 0) {
    await notifee.cancelTriggerNotifications(toCancel);
  }
}

/**
 * Cancels every scheduled notification. Used by the debug screen.
 * Production reschedules do NOT call this — they use the diff in
 * `rescheduleAll` to avoid tearing down alarms that are still desired.
 */
export async function cancelAll(): Promise<void> {
  await notifee.cancelAllNotifications();
}

/**
 * Refreshes the schedule for the given user using a diff-based algorithm:
 *
 *   1. Load active reminders from the local SQLite repo.
 *   2. Compute each Tier's desired set from its own budget — the 64 soonest
 *      medication occurrences, and separately the 32 soonest task occurrences.
 *      The two selections read disjoint sets of Reminders, so neither can
 *      shorten the other (ADR 0003).
 *   3. Read the currently-scheduled notification IDs from Notifee.
 *   4. Cancel only the notifications that are in neither desired set.
 *   5. Create only the notifications that are missing, each with its own Tier's
 *      trigger — `SET_ALARM_CLOCK` for a dose, an ordinary WorkManager-backed
 *      trigger for a task (ADR 0007).
 *
 * The previous implementation called `cancelAll()` on every app foreground
 * and rebuilt every alarm, which was wasteful and caused a churn window
 * where notifications were briefly unarmed. The diff approach is
 * idempotent: two consecutive calls with unchanged data cancel and create
 * nothing.
 *
 * Reading `reminders` rather than `medications` is what makes a `weekly`
 * schedule produce alarms at all — the POC engine had no shape for one.
 *
 * **The two Tiers must be diffed in one pass, not two.** `getTriggerNotificationIds`
 * returns every pending trigger regardless of which subsystem holds it, so a
 * pass that knew only about medications would compute the task triggers as
 * stale and cancel them, and a task-only pass would do the same to the doses.
 * One cancel pass over the union is what keeps that from being a foreground
 * away.
 */
export async function rescheduleAll(userId: string): Promise<void> {
  await armReminders(await remindersRepo.list(userId));
}

/**
 * `rescheduleAll` without the read — the diff and the arming, over a list of
 * Reminders the caller supplies.
 *
 * Split out for the device harness, which has to drive the real two-Tier
 * selection on a signed-out install where there are no rows to read. Everything
 * this ticket's guarantee depends on is in here rather than in the read above,
 * so a harness case that calls this is exercising the production path and not a
 * reimplementation of it.
 */
export async function armReminders(
  reminders: readonly Reminder[]
): Promise<void> {
  const now = Date.now();

  const desiredDoses = selectMedicationHorizon(
    reminders,
    now,
    MAX_GLOBAL_TRIGGERS
  );
  const desiredTasks = selectTaskHorizon(reminders, now, MAX_TASK_TRIGGERS);

  // Each Tier paired with the id builder its triggers are named by, so the
  // cancel pass and the two create passes below all read the same two rows
  // rather than each spelling the pairing again.
  const desired: readonly {
    readonly tier: Tier;
    readonly occurrences: readonly ScheduledOccurrence[];
    readonly idFor: (reminderId: string, fireAt: number) => string;
  }[] = [
    {
      tier: "medication",
      occurrences: desiredDoses,
      idFor: buildNotificationId,
    },
    { tier: "task", occurrences: desiredTasks, idFor: buildTaskNotificationId },
  ];

  const desiredIds = new Set(
    desired.flatMap(({ occurrences, idFor }) =>
      occurrences.map((occurrence) =>
        idFor(occurrence.reminderId, occurrence.fireAt)
      )
    )
  );
  const currentIds = new Set(await notifee.getTriggerNotificationIds());

  const toCancel: string[] = [];
  for (const id of currentIds) {
    // Snoozes live in the same Notifee namespace but are not schedule-derived,
    // so they are never in `desiredIds` and must be excluded explicitly rather
    // than swept up as stale. See `SNOOZE_NOTIFICATION_ID_PREFIX`.
    if (!desiredIds.has(id) && !id.startsWith(SNOOZE_NOTIFICATION_ID_PREFIX)) {
      toCancel.push(id);
    }
  }
  if (toCancel.length > 0) {
    await notifee.cancelTriggerNotifications(toCancel);
  }

  // Doses first, deliberately. If arming throws part-way — a permission
  // revoked, a native failure — what is already armed is the medication set.
  for (const { tier, occurrences, idFor } of desired) {
    for (const occurrence of occurrences) {
      const id = idFor(occurrence.reminderId, occurrence.fireAt);
      if (currentIds.has(id)) {
        continue;
      }
      await notifee.createTriggerNotification(
        tier === "medication"
          ? buildNotification(occurrence)
          : buildTaskNotification(occurrence),
        buildTrigger(tier, occurrence.fireAt)
      );
    }
  }
}

/**
 * Returns the number of pending trigger notifications.
 */
export async function getPendingCount(): Promise<number> {
  const ids = await notifee.getTriggerNotificationIds();
  return ids.length;
}
