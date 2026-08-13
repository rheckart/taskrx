/**
 * How this app writes a moment.
 *
 * One definition rather than three: the medication list's "last taken", the
 * Backlog's "last done" and the answer screen's "scheduled" all showed the same
 * instant in the same shape, each with its own copy of the options object. A
 * fourth surface would have made a fourth.
 *
 * The locale is the device's (`[]`), so this follows the person's own
 * conventions rather than imposing one.
 */
export function formatDayTime(ms: number): string {
  return new Date(ms).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * Local midnight of the day a `"YYYY-MM-DD"` key names.
 *
 * `computeAdherence` groups its daily buckets by local calendar date and labels
 * each one with a key in this shape, built from `getFullYear()` / `getMonth()` /
 * `getDate()`. Reading it back has to use the same calendar, and the one-liner
 * that looks like it does — `new Date("2026-08-04")` — does not: a date-only
 * string is specified to parse as UTC midnight, so every zone behind UTC renders
 * the key as the day before it. The adherence breakdown would then report
 * Tuesday's doses on Monday's row, and the further west the person is the more
 * confidently it would do it.
 *
 * Split out from `formatDayKey` so the rule can be pinned as a number rather
 * than as a formatted string, which would make the test's assertion depend on
 * the runner's locale.
 */
export function dayKeyToLocalMs(dayKey: string): number {
  const [year = 0, month = 1, day = 1] = dayKey.split("-").map(Number);
  return new Date(year, month - 1, day, 0, 0, 0, 0).getTime();
}

/**
 * How this app writes a day on its own — the adherence breakdown's row label.
 *
 * The weekday earns its place: the pattern a person is looking for in a week of
 * doses is usually "the weekend" or "the days I work late", and a bare date
 * makes them count it out.
 */
export function formatDayKey(dayKey: string): string {
  return new Date(dayKeyToLocalMs(dayKey)).toLocaleDateString([], {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
}
