/**
 * Shared TypeScript types for TaskRx.
 *
 * These shapes are used by both the frontend mobile app (`apps/mobile`) and
 * the backend sync logic. The Convex backend has its own generated document
 * types in `packages/convex/convex/_generated/`, but those are internal to the
 * `convex/` directory and should not be imported from `@taskrx/shared`.
 *
 * The `Medication` and `DoseLog` shapes that used to head this file went with
 * the legacy generation; `Reminder` and `ReminderInstance` below are the whole
 * domain now.
 */

/**
 * The medication form's schedule inputs, and the only survivors of the POC
 * types.
 *
 * They are NOT the domain schedule — that is `ScheduleTimes`, a tagged union
 * that also covers `weekly`. These two are the shapes `SchedulePicker` edits,
 * discriminated by a separate `scheduleType` prop rather than by a field, and
 * `meds/new.tsx` converts them to `ScheduleTimes` on save.
 *
 * They stay here rather than moving into the app because the picker and the
 * form both import them, and they are due to be replaced wholesale when the
 * picker grows the `once` and `none` variants.
 */
export type ScheduleType = "daily" | "interval";

export interface DailySchedule {
  readonly times: readonly string[];
}

export interface IntervalSchedule {
  readonly hours: number;
  readonly startAt: number;
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

/**
 * The reminder domain, ported from `TaskRx.Core`. `Reminder` carries a `kind`
 * of `medication` or `task` and a `ScheduleTimes` union that includes weekly
 * schedules. Since the legacy generation was deleted this is the only domain —
 * there is no longer a second set to migrate across from.
 */
export {
  INSTANCE_STATUSES,
  NOTIFICATION_TYPES,
  PRIORITIES,
  REMINDER_KINDS,
  assertNever,
  createCaregiver,
  createReminder,
  createReminderInstance,
  dailyTimes,
  intervalTimes,
  parseScheduleTimes,
  serializeScheduleTimes,
  weeklyTimes,
} from "./domain/index";
export type {
  Caregiver,
  CreateCaregiverInput,
  CreateReminderInput,
  CreateReminderInstanceInput,
  DailyTimes,
  DailyTimesWire,
  InstanceStatus,
  IntervalTimes,
  IntervalTimesWire,
  NotificationType,
  Priority,
  Reminder,
  ReminderInstance,
  ReminderKind,
  ScheduleTimes,
  ScheduleTimesWire,
  Weekday,
  WeeklyTimes,
  WeeklyTimesWire,
} from "./domain/index";

export { nextOccurrences, selectUpcomingOccurrences } from "./schedule/index";
export type { OccurrenceSource, ScheduledOccurrence } from "./schedule/index";

/**
 * Adherence scoring and refill planning, ported from `TaskRx.Core`. Both are
 * pure functions over the unified `Reminder` domain above: the caller supplies
 * the clock and any generated ids.
 */
export { computeAdherence } from "./adherence/calculator";
export type { AdherenceStats, DayAdherence } from "./adherence/calculator";

export { planRefill } from "./refill/planner";
export type { PlanRefillOptions, RefillDecision } from "./refill/planner";

/**
 * The natural-language reminder parser, ported from `TaskRx.Core`. Both entry
 * points are pure: the caller supplies the clock and any generated ids.
 *
 * `parseReminder` reads a whole command ("aspirin 100mg mwf at 9am") and
 * returns a `Reminder`; `parseScheduleGrammar` reads just the schedule half.
 * The two are independent — `ReminderParser.cs` never references
 * `ScheduleGrammarParser`, so `parseReminder` carries its own schedule
 * handling and does not defer to `parseScheduleGrammar`.
 */
export { parseReminder } from "./parser/reminder-parser";
export type {
  ParseReminderOptions,
  ReminderParseResult,
} from "./parser/reminder-parser";

export {
  isGrammarToken,
  parseScheduleGrammar,
} from "./parser/schedule-grammar";
export type {
  ScheduleParseFailure,
  ScheduleParseResult,
  ScheduleParseSuccess,
} from "./parser/schedule-grammar";

/**
 * The timeline projection — the union of computed Occurrences and stored
 * Instances that the home surface renders. Pure, Kind-neutral, and the only
 * place the rules of "what does my day look like" live.
 */
export { projectTimeline, timelineRowKey } from "./timeline/projection";
export type {
  ProjectTimelineInput,
  TimelineRow,
  TimelineWindow,
} from "./timeline/projection";

export {
  shouldApplyRemoteReminder,
  shouldApplyRemoteReminderInstance,
} from "./sync/conflict";
export type {
  ReminderInstanceWithConflictFields,
  ReminderWithTimestamps,
} from "./sync/conflict";

export {
  ANDROID_ALARM_DISABLED,
  ANDROID_ALARM_ENABLED,
  ANDROID_ALARM_NOT_SUPPORTED,
  NOTIFICATION_AUTH_AUTHORIZED,
  NOTIFICATION_AUTH_DENIED,
  NOTIFICATION_AUTH_NOT_DETERMINED,
  NOTIFICATION_AUTH_PROVISIONAL,
  derivePermissionStatus,
  isExactAlarmGranted,
} from "./notifications/permission-state";
export type {
  AndroidAlarmSetting,
  NotificationAuthorization,
  PermissionSnapshot,
  PermissionSnapshotInput,
  PermissionStatus,
} from "./notifications/permission-state";
