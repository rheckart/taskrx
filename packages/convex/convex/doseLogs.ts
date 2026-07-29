import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";

/**
 * Dose log queries and mutations.
 *
 * All functions enforce per-user access via `getCurrentUserId`. Writes verify
 * ownership before mutating a document.
 *
 * The `localId` field stores the stable UUID generated on the device so the
 * sync worker can map local SQLite rows to Convex documents and retry creates
 * idempotently. This deviates from the original POC schema (see schema.ts).
 */

const doseLogValidator = v.object({
  _id: v.id("doseLogs"),
  _creationTime: v.number(),
  userId: v.string(),
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
});

void doseLogValidator;

export const list = query({
  args: {
    medicationId: v.optional(v.id("medications")),
    status: v.optional(
      v.union(
        v.literal("pending"),
        v.literal("taken"),
        v.literal("skipped"),
        v.literal("missed")
      )
    ),
    since: v.optional(v.number()),
    until: v.optional(v.number()),
  },
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);

    const { medicationId, status, since, until } = args;

    let queryBuilder = ctx.db
      .query("doseLogs")
      .withIndex("by_user", (q) => q.eq("userId", userId));

    if (medicationId !== undefined) {
      queryBuilder = queryBuilder.filter((q) =>
        q.eq(q.field("medicationId"), medicationId)
      );
    }
    if (status !== undefined) {
      queryBuilder = queryBuilder.filter((q) =>
        q.eq(q.field("status"), status)
      );
    }
    if (since !== undefined) {
      queryBuilder = queryBuilder.filter((q) =>
        q.gte(q.field("scheduledTime"), since)
      );
    }
    if (until !== undefined) {
      queryBuilder = queryBuilder.filter((q) =>
        q.lte(q.field("scheduledTime"), until)
      );
    }

    return await queryBuilder.collect();
  },
});

export const listForMedication = query({
  args: {
    medicationId: v.id("medications"),
  },
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    return await ctx.db
      .query("doseLogs")
      .withIndex("by_medication", (q) =>
        q.eq("medicationId", args.medicationId)
      )
      .filter((q) => q.eq(q.field("userId"), userId))
      .collect();
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    return await ctx.db
      .query("doseLogs")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .filter((q) => q.eq(q.field("userId"), userId))
      .first();
  },
});

export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    medicationId: v.id("medications"),
    scheduledTime: v.number(),
    status: v.union(
      v.literal("pending"),
      v.literal("taken"),
      v.literal("skipped"),
      v.literal("missed")
    ),
    takenAt: v.optional(v.number()),
    notes: v.optional(v.string()),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create dose log for another user");
    }

    const medication = await ctx.db.get(args.medicationId);
    if (medication?.userId !== currentUserId) {
      throw new Error("Medication not found");
    }

    const existing = await ctx.db
      .query("doseLogs")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error("Dose log localId already exists for another user");
      }

      /**
       * An upsert, not a no-op — see `reminders.create` for the full rationale.
       *
       * This table is the one where discarding the payload is worst: a dose
       * logged offline and then marked taken before the first create drained
       * would have kept `status: "pending"` on the server forever, so a second
       * device would show a dose the patient has already taken as still due.
       *
       * `medicationId` is rewritten along with the rest because the ownership
       * check above already validated it against the caller. `localId`,
       * `userId` and `createdAt` are identity and are never rewritten.
       */
      await ctx.db.patch(existing._id, {
        medicationId: args.medicationId,
        scheduledTime: args.scheduledTime,
        status: args.status,
        updatedAt: args.updatedAt,
        ...(args.takenAt !== undefined ? { takenAt: args.takenAt } : {}),
        ...(args.notes !== undefined ? { notes: args.notes } : {}),
      });
      return existing._id;
    }

    return await ctx.db.insert("doseLogs", {
      userId: currentUserId,
      localId: args.localId,
      medicationId: args.medicationId,
      scheduledTime: args.scheduledTime,
      status: args.status,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
      ...(args.takenAt !== undefined ? { takenAt: args.takenAt } : {}),
      ...(args.notes !== undefined ? { notes: args.notes } : {}),
    });
  },
});

export const markTaken = mutation({
  args: {
    id: v.id("doseLogs"),
    takenAt: v.optional(v.number()),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const doseLog = await ctx.db.get(args.id);
    if (doseLog?.userId !== userId) {
      throw new Error("Dose log not found");
    }

    await ctx.db.patch(args.id, {
      status: "taken",
      takenAt: args.takenAt ?? Date.now(),
      updatedAt: Date.now(),
    });

    return args.id;
  },
});

export const markSkipped = mutation({
  args: {
    id: v.id("doseLogs"),
    notes: v.optional(v.string()),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const doseLog = await ctx.db.get(args.id);
    if (doseLog?.userId !== userId) {
      throw new Error("Dose log not found");
    }

    await ctx.db.patch(args.id, {
      status: "skipped",
      notes: args.notes,
      updatedAt: Date.now(),
    });

    return args.id;
  },
});

export const markMissed = mutation({
  args: {
    id: v.id("doseLogs"),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const doseLog = await ctx.db.get(args.id);
    if (doseLog?.userId !== userId) {
      throw new Error("Dose log not found");
    }

    await ctx.db.patch(args.id, {
      status: "missed",
      updatedAt: Date.now(),
    });

    return args.id;
  },
});
