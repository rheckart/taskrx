/**
 * A single computed dose for a medication.
 *
 * This shape is produced by the pure schedule engine and consumed by the
 * notification scheduler (Day 4B) and the sync worker (Day 7). It contains no
 * DB state and no side effects.
 */
export interface ScheduledDose {
  readonly medicationId: string;
  readonly scheduledTime: number; // unix ms
}

/**
 * A single computed occurrence of a reminder, ported from
 * `TaskRx.Core/ScheduledOccurrence.cs`.
 *
 * Unlike `ScheduledDose` this carries the display fields the notification
 * payload needs, so the scheduler does not have to re-read the reminder to
 * build a notification. `Dosage` is empty for `task` reminders.
 */
export interface ScheduledOccurrence {
  readonly occurrenceId: string;
  readonly reminderId: string;
  readonly name: string;
  readonly dosage: string | undefined;
  readonly fireAt: number; // unix ms
}
