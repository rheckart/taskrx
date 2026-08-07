import type { ScheduleTimes } from "@taskrx/shared";

/**
 * How this app says a Schedule out loud.
 *
 * One definition rather than two: the medication list has rendered a schedule
 * since the cutover, and natural-language entry has to show the same sentence
 * back before saving — "what the parser understood" is unreadable as a JSON
 * blob and unreviewable as a picker alone, because a picker shows the parts
 * without saying what they add up to.
 *
 * It takes a `ScheduleTimes`, not a `Reminder`. The entry surface holds a
 * `ReminderDraft`, which is deliberately not a Reminder — it has no id and no
 * owner yet — and a function that only ever reads one field has no business
 * demanding the whole aggregate.
 *
 * Presentation, so it lives here and not in `@taskrx/shared`: it reaches for
 * the device locale and the device time zone, which is exactly what a pure,
 * replayable package must not do.
 */

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;

export function describeSchedule(schedule: ScheduleTimes): string {
  switch (schedule.type) {
    case "daily":
      return schedule.times.length === 0
        ? "Daily (no times set)"
        : `Daily at ${schedule.times.join(", ")}`;
    case "weekly": {
      if (schedule.daysOfWeek.length === 0 || schedule.times.length === 0) {
        return "Weekly (not fully set)";
      }
      // `Weekday` is `0 | 1 | ... | 6` and `DAY_NAMES` has seven entries, so
      // the index is total and needs no fallback.
      const days = schedule.daysOfWeek.map((d) => DAY_NAMES[d]);
      return `${days.join(", ")} at ${schedule.times.join(", ")}`;
    }
    case "interval": {
      const start = new Date(schedule.startAt);
      const startLabel = `${start.getHours().toString().padStart(2, "0")}:${start.getMinutes().toString().padStart(2, "0")}`;
      return `Every ${String(schedule.hours)}h starting ${startLabel}`;
    }
    case "once":
      return `Once, ${new Date(schedule.at).toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })}`;
    case "none":
      return "In your backlog — no time set";
  }
}
