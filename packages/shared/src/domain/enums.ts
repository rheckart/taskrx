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
 * `TaskRx.Core.InstanceStatus`, with one member renamed.
 *
 * `completed` is what the C# called `Taken`. Both Kinds share this list and a
 * task is never "taken", so keeping the medication spelling would have pushed a
 * Kind check into every surface that displays a status — the per-consumer cost
 * a unified timeline exists to remove. See ADR 0001, which also records why
 * relabelling in the UI, or adding `done` alongside `taken`, were rejected. A
 * medication is still *shown* as taken; only the stored vocabulary is
 * Kind-neutral.
 *
 * `snoozed` is deliberately distinct from `skipped` and `missed`: the adherence
 * calculator filters non-`pending` instances and then counts only
 * completed/missed/skipped, which is what makes a snooze affect a medication
 * streak without counting as a miss.
 */
export const INSTANCE_STATUSES = [
  "pending",
  "completed",
  "snoozed",
  "skipped",
  "missed",
] as const;

export type InstanceStatus = (typeof INSTANCE_STATUSES)[number];

/**
 * `TaskRx.Core.NotificationType`, used by the notification log.
 *
 * `taken` here is NOT the `InstanceStatus` member ADR 0001 renamed. This enum
 * records what happened to a notification, not what became of an Instance, and
 * the rename deliberately did not reach it. `reminder.test.ts` pins both lists
 * so the difference cannot be "tidied away" by a blanket search-and-replace —
 * which is exactly how it was nearly lost.
 */
export const NOTIFICATION_TYPES = [
  "fired",
  "taken",
  "snoozed",
  "skipped",
  "missed",
  "caregiver_pinged",
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];
