/**
 * A single computed occurrence of a reminder, ported from
 * `TaskRx.Core/ScheduledOccurrence.cs`.
 *
 * It carries the display fields the notification payload needs, so the
 * scheduler does not have to re-read the reminder to build a notification.
 * `Dosage` is empty for `task` reminders.
 *
 * A medication-only predecessor, `ScheduledDose`, carried just an id and a
 * time; it went with the legacy generation along with the POC `engine.ts` that
 * produced it.
 */
export interface ScheduledOccurrence {
  readonly occurrenceId: string;
  readonly reminderId: string;
  readonly name: string;
  readonly dosage: string | undefined;
  readonly fireAt: number; // unix ms
}
