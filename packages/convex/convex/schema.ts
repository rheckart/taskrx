import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

/**
 * Convex schema for TaskRx.
 *
 * One generation of tables. A POC pair, `medications` / `doseLogs`, used to sit
 * alongside these; it was deleted once nothing read or wrote it, together with
 * its device-side tables (migration 009) and its query/mutation modules.
 *
 * `reminders` / `reminderInstances` match the domain types in
 * `packages/shared/src/domain/reminder.ts`. The difference that motivated
 * replacing the POC pair is `kind`: one reminder is either a medication or a
 * task, where `medications` could only express the former. Weekly schedules,
 * priority, tags, refill tracking, caregiver fan-out, and snoozing arrived with
 * it. The Postgres target this shape came from is on the archive branch, at
 * `src/TaskRx.Data/Migrations/M0001_InitialSchema.cs`.
 *
 * The field validators are exported rather than inlined into `defineTable` so
 * the query/mutation modules can build argument and return validators from the
 * same source and cannot drift from the stored shape.
 */

/** `ReminderKind` — the axis the POC medication shape did not have. */
export const reminderKindValidator = v.union(
  v.literal("medication"),
  v.literal("task")
);

/** `Priority`, ordered most to least urgent, as in the domain enum. */
export const priorityValidator = v.union(
  v.literal("critical"),
  v.literal("high"),
  v.literal("normal"),
  v.literal("low")
);

/**
 * `InstanceStatus`. `completed` is what a medication-only model would have
 * called "taken"; both Kinds share this list and a task is never taken, so the
 * stored vocabulary is Kind-neutral (ADR 0001). `snoozed` is deliberately
 * distinct from `skipped` and `missed` so a deferred dose does not count
 * against adherence.
 */
export const instanceStatusValidator = v.union(
  v.literal("pending"),
  v.literal("completed"),
  v.literal("snoozed"),
  v.literal("skipped"),
  v.literal("missed")
);

/**
 * `Weekday` — spelled as literals rather than `v.number()` so the server
 * rejects an out-of-range day at the validator boundary instead of storing it.
 * The ordinals match both `Date.getDay()` and C#'s `DayOfWeek` (Sunday = 0).
 */
const weekdayValidator = v.union(
  v.literal(0),
  v.literal(1),
  v.literal(2),
  v.literal(3),
  v.literal(4),
  v.literal(5),
  v.literal(6)
);

/**
 * `ScheduleTimes` as a discriminated union on the `type` tag.
 *
 * This replaces the POC's `scheduleTimes: v.any()`, which accepted any payload
 * at all — a weekly schedule with a malformed `daysOfWeek` would round-trip
 * through the backend untouched and only fail on the device. Spelling the
 * union out means Convex rejects the write, and the generated `Doc` type is a
 * tagged union the handlers can `switch` over exhaustively.
 *
 * Times are `"HH:MM"` local wall-clock strings; `startAt` and `at` are unix ms.
 *
 * `once` and `none` widen the union with the domain one in `@taskrx/shared`
 * (issue #12); both are closed unions and the spec requires them to move
 * together, so a schedule this validator admits is one the device can express
 * and vice versa. `once` carries an instant rather than an optional date — see
 * ADR 0006 — and `none` is the Backlog: a Reminder that produces no Occurrences
 * at all, which until now was only expressible by accident as a `daily`
 * schedule with no times.
 */
export const scheduleTimesValidator = v.union(
  v.object({
    type: v.literal("daily"),
    times: v.array(v.string()),
  }),
  v.object({
    type: v.literal("interval"),
    hours: v.number(),
    startAt: v.number(),
  }),
  v.object({
    type: v.literal("weekly"),
    daysOfWeek: v.array(weekdayValidator),
    times: v.array(v.string()),
  }),
  v.object({
    type: v.literal("once"),
    at: v.number(),
  }),
  v.object({
    type: v.literal("none"),
  })
);

/**
 * Stored fields of a `reminders` document.
 *
 * `userId` is the Clerk subject; `localId` is the stable UUID the device
 * generated, which is what makes `create` idempotent across app restarts (see
 * `reminders.create`). Neither is part of the domain `Reminder` — there, the
 * device UUID *is* the identity, while here Convex assigns `_id` and `localId`
 * is the join key back to the SQLite row.
 *
 * `rxcui` and `deletedAt` are likewise outside the domain type: `rxcui` carried
 * the RxNorm code forward from the POC medication shape so the migration would
 * not drop it,
 * and `deletedAt` is the soft-delete tombstone the sync layer needs to
 * propagate a deletion to other devices.
 */
export const reminderFields = {
  userId: v.string(),
  localId: v.string(),
  kind: reminderKindValidator,
  name: v.string(),
  rxcui: v.optional(v.string()),
  dosage: v.optional(v.string()),
  instructions: v.optional(v.string()),
  schedule: scheduleTimesValidator,
  priority: priorityValidator,
  tags: v.array(v.string()),
  pillsRemaining: v.optional(v.number()),
  pillsPerDose: v.optional(v.number()),
  refillReminderDays: v.optional(v.number()),
  caregiverIds: v.array(v.string()),
  active: v.boolean(),
  createdAt: v.number(),
  updatedAt: v.number(),
  deletedAt: v.optional(v.number()),
};

/**
 * Stored fields of a `reminderInstances` document.
 *
 * `reminderKind` is denormalized from the parent reminder so a day view can be
 * filtered by kind without loading every reminder. `reminderInstances.create`
 * reads it off the parent rather than accepting it from the client, so the two
 * cannot disagree.
 */
export const reminderInstanceFields = {
  userId: v.string(),
  localId: v.string(),
  reminderId: v.id("reminders"),
  reminderKind: reminderKindValidator,
  scheduledTime: v.number(),
  status: instanceStatusValidator,
  completedAt: v.optional(v.number()),
  snoozedUntil: v.optional(v.number()),
  snoozeCount: v.number(),
  notes: v.optional(v.string()),
  createdAt: v.number(),
  updatedAt: v.number(),
};

export default defineSchema({
  // `by_user_kind` is the unified model's own index: "show me my tasks" and
  // "show me my medications" are separate screens, and without it that split
  // is a full scan of the user's reminders.
  reminders: defineTable(reminderFields)
    .index("by_user", ["userId"])
    .index("by_user_active", ["userId", "active"])
    .index("by_user_kind", ["userId", "kind"])
    .index("by_local_id", ["localId"]),

  // Convex has no partial indexes, so the archived
  // `ix_instances_pending_status` — the hot path for missed-dose detection —
  // becomes `by_user_status_time`, which is prefix-scanned at
  // (userId, "pending") and ordered by time within it.
  reminderInstances: defineTable(reminderInstanceFields)
    .index("by_user", ["userId"])
    .index("by_reminder", ["reminderId"])
    .index("by_user_time", ["userId", "scheduledTime"])
    .index("by_user_status_time", ["userId", "status", "scheduledTime"])
    .index("by_local_id", ["localId"]),
});
