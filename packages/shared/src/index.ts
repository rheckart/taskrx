/**
 * Shared TypeScript types for the TaskRx POC.
 *
 * These shapes are used by both the frontend mobile app (`apps/mobile`) and
 * the backend sync logic. The Convex backend has its own generated document
 * types in `packages/convex/convex/_generated/`, but those are internal to the
 * `convex/` directory and should not be imported from `@taskrx/shared`.
 *
 * Field names use snake_case so they align with the local SQLite schema
 * (`apps/mobile/src/db/migrations/001_init.sql`). The sync layer translates
 * between snake_case (SQLite / shared types) and camelCase (Convex schema).
 */

export type ScheduleType = "daily" | "interval";

export interface DailySchedule {
  readonly times: readonly string[];
}

export interface IntervalSchedule {
  readonly hours: number;
  readonly startAt: number;
}

export interface Medication {
  readonly id: string;
  readonly userId: string;
  readonly name: string;
  readonly rxcui: string | undefined;
  readonly dosage: string | undefined;
  readonly scheduleType: ScheduleType;
  readonly scheduleTimes: DailySchedule | IntervalSchedule;
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deletedAt: number | undefined;
}

export type DoseStatus = "pending" | "taken" | "skipped" | "missed";

export interface DoseLog {
  readonly id: string;
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
  readonly takenAt: number | undefined;
  readonly status: DoseStatus;
  readonly notes: string | undefined;
  readonly createdAt: number;
  readonly updatedAt: number;
}

/**
 * RxNorm search result shape returned by the Convex `rxnorm.search` action.
 *
 * The action maps the RxNorm approximateTerm.json response down to a stable,
 * UI-friendly shape.
 */
export interface RxNormSearchResult {
  readonly rxcui: string;
  readonly name: string;
}

export type RxNormSearchResponse = readonly RxNormSearchResult[];

export { nextNDaily, nextNInterval, upcomingDosesFor } from "./schedule/index";
export type { ScheduledDose } from "./schedule/index";
