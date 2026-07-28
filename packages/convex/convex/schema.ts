import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

/**
 * Convex schema for TaskRx.
 *
 * Two generations of tables live here at once.
 *
 * `medications` / `doseLogs` are the POC shape (`.omo/plans/poc-plan.md` §6.2),
 * mirrored by local SQLite in `apps/mobile/src/db/migrations/001_init.sql`.
 * They are kept readable until the device-side data migration has run.
 *
 * `reminders` / `reminderInstances` are the unified shape, matching the domain
 * types in `packages/shared/src/domain/reminder.ts`. The difference that
 * motivates the widening is `kind`: one reminder is either a medication or a
 * task, where `medications` can only express the former. Weekly schedules,
 * priority, tags, refill tracking, caregiver fan-out, and snoozing arrive with
 * it. The Postgres target this shape came from is on the archive branch, at
 * `src/TaskRx.Data/Migrations/M0001_InitialSchema.cs`.
 *
 * The field validators are exported rather than inlined into `defineTable` so
 * the query/mutation modules can build argument and return validators from the
 * same source and cannot drift from the stored shape.
 */

/** `ReminderKind` — the axis `medications` does not have. */
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
 * `InstanceStatus`. `snoozed` is deliberately distinct from `skipped` and
 * `missed` so a deferred dose does not count against adherence.
 */
export const instanceStatusValidator = v.union(
  v.literal("pending"),
  v.literal("taken"),
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
 * Times are `"HH:MM"` local wall-clock strings; `startAt` is unix ms.
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
 * `rxcui` and `deletedAt` are likewise outside the domain type: `rxcui` carries
 * the RxNorm code forward from `medications` so the migration does not drop it,
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
  takenAt: v.optional(v.number()),
  snoozedUntil: v.optional(v.number()),
  snoozeCount: v.number(),
  notes: v.optional(v.string()),
  createdAt: v.number(),
  updatedAt: v.number(),
};

export default defineSchema({
  medications: defineTable({
    // Clerk user ID, extracted from the JWT subject by the auth helper.
    userId: v.string(),
    // Stable local UUID from the device SQLite row. Used by the sync worker to
    // map local ids to Convex-generated ids and to make create operations
    // idempotent across app restarts.
    localId: v.string(),
    name: v.string(),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    scheduleType: v.union(v.literal("daily"), v.literal("interval")),
    // JSON payload whose shape matches the SQLite `schedule_times` column:
    // daily: { times: string[] }
    // interval: { hours: number; startAt: number }
    scheduleTimes: v.any(),
    active: v.boolean(),
    createdAt: v.number(),
    updatedAt: v.number(),
    deletedAt: v.optional(v.number()),
  })
    .index("by_user", ["userId"])
    .index("by_user_active", ["userId", "active"])
    .index("by_local_id", ["localId"]),

  doseLogs: defineTable({
    userId: v.string(),
    // Stable local UUID from the device SQLite row. See medications.localId.
    localId: v.string(),
    medicationId: v.id("medications"),
    scheduledTime: v.number(),
    takenAt: v.optional(v.number()),
    status: v.union(
      v.literal("pending"),
      v.literal("taken"),
      v.literal("skipped"),
      v.literal("missed")
    ),
    notes: v.optional(v.string()),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_medication", ["medicationId"])
    .index("by_user_time", ["userId", "scheduledTime"])
    .index("by_local_id", ["localId"]),

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
