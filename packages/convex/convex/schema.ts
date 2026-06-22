import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

/**
 * Convex schema for the TaskRx POC.
 *
 * Matches the data model defined in `.omo/plans/poc-plan.md` §6.2.
 * Local SQLite (`apps/mobile/src/db/migrations/001_init.sql`) stores the
 * same logical shape using snake_case; the sync layer translates between
 * the two representations.
 */
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
});
