import { v } from "convex/values";

import { internalMutation } from "./_generated/server";

/**
 * Hard-deletes the rows the adb notification harness seeds.
 *
 * Every run of harness case 8 or 9 creates a real reminder and answers a real
 * dose against it (`archive/expo-rn:apps/mobile/scripts/notification-harness.sh`), and both
 * push to Convex like any other write. The harness's own `cleanup-seeded`
 * action soft-deletes the reminder, which is the right thing on a user's
 * device — a deleted medication is history, not a mistake — but it leaves the
 * rows here, and the fabricated `reminderInstances` rows behind them are the
 * ones that matter: adherence is computed from exactly those, so a few dozen
 * invented doses quietly move a real number.
 *
 * This is the ONLY hard delete in this deployment, which is why it is
 * `internalMutation`: unreachable from any client, runnable only from the CLI
 * or dashboard (`npx convex run harnessData:purgeSeeded '{}'`).
 *
 * Not user-scoped. It runs with no identity to scope to, and a reminder
 * carrying the marker is rig output whoever owns it.
 */

/**
 * Must equal `HARNESS_REMINDER_MARKER` in
 * `archive/expo-rn:apps/mobile/src/notifications/harness.ts`, which is what writes these names.
 * The two cannot share a constant — this package does not depend on the mobile
 * app — so the string is pinned by a test on each side instead.
 *
 * Matched as a PREFIX, never as a substring: "My [harness] notes" is a name a
 * person could plausibly type, and the only thing separating this function from
 * someone's medication history is that it matches the start.
 */
const HARNESS_REMINDER_MARKER = "[harness]";

/**
 * Bound on one invocation. There is no index on a name prefix, so finding the
 * seeded rows means reading `reminders` — which is exactly the unbounded scan
 * that fails a deployment once the table is real. Taking a page at a time keeps
 * the read inside Convex's limits; run it again if it reports a full page.
 */
const SCAN_LIMIT = 500;

export const purgeSeeded = internalMutation({
  args: {},
  returns: v.object({ reminders: v.number(), instances: v.number() }),
  handler: async (ctx) => {
    const scanned = await ctx.db.query("reminders").take(SCAN_LIMIT);
    const seeded = scanned.filter((reminder) =>
      reminder.name.startsWith(HARNESS_REMINDER_MARKER)
    );

    let instances = 0;
    for (const reminder of seeded) {
      // Children first: an instance whose parent is gone is exactly the
      // orphan this function exists to prevent, and deleting the parent first
      // would leave one behind on any failure in between.
      const children = await ctx.db
        .query("reminderInstances")
        .withIndex("by_reminder", (q) => q.eq("reminderId", reminder._id))
        .collect();
      for (const child of children) {
        await ctx.db.delete(child._id);
        instances += 1;
      }
      await ctx.db.delete(reminder._id);
    }

    return { reminders: seeded.length, instances };
  },
});
