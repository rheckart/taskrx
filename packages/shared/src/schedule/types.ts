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
