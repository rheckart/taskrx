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
