import { cronJobs } from "convex/server";

import { internal } from "./_generated/api.js";

/**
 * The mailbox poll. Every five minutes, as the plan chose: a notice that arrives by email
 * should reach the phone while the person can still act on it, and a five-minute lag is
 * invisible next to a collection day.
 *
 * The action is a no-op when `JMAP_TOKEN` is unset, so this cron is harmless on a
 * deployment with no mailbox.
 */
const crons = cronJobs();

crons.interval("mailbox poll", { minutes: 5 }, internal.mailbox.poll, {});

export default crons;
