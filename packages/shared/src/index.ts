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
  ANSWERABLE_STATUSES,
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
  isAnswerable,
  isWellFormedSchedule,
  noneTimes,
  onceTimes,
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
  NoneTimes,
  NoneTimesWire,
  NotificationType,
  OnceTimes,
  OnceTimesWire,
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

export {
  nextOccurrences,
  selectMedicationHorizon,
  selectTaskHorizon,
} from "./schedule/index";
export type { OccurrenceSource, ScheduledOccurrence } from "./schedule/index";

/**
 * Tier — the delivery guarantee a Reminder's notifications get, derived from
 * Kind and from nothing else. See ADR 0003: a task is not in the medication
 * Horizon at all, so it cannot displace a dose.
 *
 * All of it is exported now, because #14 gave the task Tier a trigger. The two
 * budgets answer "which set does this Reminder belong to", and `Tier` itself is
 * finally a value worth holding: `apps/mobile` keys its channel, importance,
 * press action and alarm primitive off it in one record, which is what stops
 * "medication or task?" being asked five times in a row at each arming site.
 *
 * `TIERS` stays inside the module — nothing outside has needed to enumerate
 * them.
 */
export {
  entersMedicationHorizon,
  entersTaskHorizon,
  tierFor,
} from "./notifications/tier";
export type { Tier } from "./notifications/tier";

/**
 * Adherence scoring and refill planning, ported from `TaskRx.Core`. Both are
 * pure functions over the unified `Reminder` domain above: the caller supplies
 * the clock and any generated ids.
 */
export { computeAdherence } from "./adherence/calculator";
export type { AdherenceStats, DayAdherence } from "./adherence/calculator";

/**
 * The window Adherence is reported over — seven local calendar days, the same
 * span ADR 0005 gave the sweep, so every day the figure covers is one an
 * unanswered dose could still be recorded against.
 */
export { ADHERENCE_PERIOD_DAYS, adherencePeriod } from "./adherence/period";
export type { AdherencePeriod } from "./adherence/period";

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

/**
 * The reviewable draft between a typed phrase and a saved Reminder — what the
 * natural-language entry surface shows before it writes anything, and the rules
 * for correcting it. `parseReminder` returns a whole `Reminder`; a draft is only
 * the part a person can see and put right.
 */
export { isSavableDraft, readPhrase, withKind, withName } from "./parser/draft";
export type {
  PhraseReading,
  ReadPhraseOptions,
  ReminderDraft,
} from "./parser/draft";

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

/**
 * The missed sweep — the rule that stops an unanswered moment from ceasing to
 * exist, and the two values ADR 0005 settled for it. Pure; the caller persists
 * what it decides and supplies the clock.
 */
export {
  GRACE_PERIOD_MS,
  SWEEP_LOOKBACK_MS,
  planMissedSweep,
} from "./sweep/planner";
export type { MissedVerdict, PlanMissedSweepInput } from "./sweep/planner";

/**
 * The Backlog — Reminders with no Schedule, and what completing one records.
 * See ADR 0002 for why the Instance is minted at the moment of the act.
 */
export { isBacklog, planBacklogCompletion } from "./backlog/completion";
export type { BacklogCompletion } from "./backlog/completion";

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
  derivePermissionGap,
  derivePermissionStatus,
  isExactAlarmGranted,
  shouldRequestNotificationPermission,
} from "./notifications/permission-state";
export type {
  AndroidAlarmSetting,
  NotificationAuthorization,
  PermissionGap,
  PermissionGateInput,
  PermissionRemedy,
  PermissionSeverity,
  PermissionSnapshot,
  PermissionSnapshotInput,
  PermissionStatus,
} from "./notifications/permission-state";
