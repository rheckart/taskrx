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
 * The statuses a person can still act on.
 *
 * `pending` is the obvious one. `snoozed` is the one worth stating: a snooze is
 * a deferral, not an answer — "not now" rather than "no" — so the moment is
 * still outstanding and the row it produced is the row a later visit settles.
 * Treating a snooze as answered would make snoozing the one way to put a moment
 * permanently out of reach, and would open a second Instance beside it every
 * time the person came back.
 *
 * `completed` and `skipped` are answers. `missed` is the sweep's verdict rather
 * than the person's, and is excluded for now with the rest — a moment that ran
 * out of time is reopened rather than reused, which is the behaviour it has
 * always had. Widening this to admit it belongs with the duplicate-instance
 * work in #21, where reopening an answered moment is the subject.
 *
 * It lives here rather than in the two callers because they would otherwise
 * each carry a copy: the on-device repository builds its `WHERE status IN (…)`
 * from it, and the timeline decides from it whether a row is still tappable. A
 * row the list offers and the lookup refuses is a button that does nothing.
 */
export const ANSWERABLE_STATUSES = ["pending", "snoozed"] as const;

export function isAnswerable(status: InstanceStatus): boolean {
  return (ANSWERABLE_STATUSES as readonly InstanceStatus[]).includes(status);
}

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
