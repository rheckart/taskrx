/**
 * The unified reminder domain enums, ported from `TaskRx.Core/Enums.cs` on the
 * `archive/dotnet-maui` branch.
 *
 * The C# side models these as real enums serialized by `JsonStringEnumConverter`,
 * so on the wire they are PascalCase member names (`"Medication"`, `"Normal"`).
 * Here they are string-literal unions in lowercase `snake_case`, matching the
 * convention the POC types and the local SQLite schema already use. The mapping
 * between the two spellings is mechanical: PascalCase member name <-> snake_case
 * literal.
 *
 * Each union is derived from a `const` tuple so the members are available at
 * runtime for validation and ordering, and the type cannot drift from the list.
 */

/** `TaskRx.Core.ReminderKind`. The axis the POC's medication-only types lack. */
export const REMINDER_KINDS = ["medication", "task"] as const;

export type ReminderKind = (typeof REMINDER_KINDS)[number];

/** `TaskRx.Core.Priority`, ordered most to least urgent, as in the C# enum. */
export const PRIORITIES = ["critical", "high", "normal", "low"] as const;

export type Priority = (typeof PRIORITIES)[number];

/**
 * `TaskRx.Core.InstanceStatus`.
 *
 * `snoozed` is deliberately distinct from `skipped` and `missed`: the adherence
 * calculator (todo 18) filters non-`pending` instances and then counts only
 * taken/missed/skipped, which is what makes a snooze affect a medication streak
 * without counting as a miss.
 */
export const INSTANCE_STATUSES = [
  "pending",
  "taken",
  "snoozed",
  "skipped",
  "missed",
] as const;

export type InstanceStatus = (typeof INSTANCE_STATUSES)[number];

/** `TaskRx.Core.NotificationType`, used by the notification log. */
export const NOTIFICATION_TYPES = [
  "fired",
  "taken",
  "snoozed",
  "skipped",
  "missed",
  "caregiver_pinged",
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];
