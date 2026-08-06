/**
 * `ScheduleTimes` — the polymorphic schedule shape, ported from
 * `TaskRx.Core/ScheduleTimes.cs` on the `archive/dotnet-maui` branch.
 *
 * The C# version models this as an abstract record with three sealed
 * subrecords, and reconstructs the runtime type on deserialization through
 * `ScheduleTimesJsonConverter`'s `$type` discriminator. TypeScript has no
 * record inheritance to lean on, so this is a tagged union with a literal
 * `type` field: `switch` over it is exhaustively checked by the compiler, and
 * an unhandled variant is a build error rather than a silent runtime fallback.
 *
 * Two representations are deliberately kept apart:
 *
 * - the **domain** shape (`ScheduleTimes`), which uses the conventions the rest
 *   of this package already uses — lowercase tags, `"HH:MM"` local wall-clock
 *   times, and unix-ms instants;
 * - the **wire** shape (`ScheduleTimesWire`), which mirrors what
 *   `ScheduleTimesJsonConverter.Write` emits byte-for-byte in structure:
 *   a `$type` discriminator plus PascalCase members.
 *
 * `Weekly` is the capability the POC's medication-only types do not have.
 */

/** Day index, matching both C#'s `DayOfWeek` and JS `Date.getDay()`. */
export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6;

/** `DailyTimes(IReadOnlyList<TimeOnly> Times)` — fixed local times every day. */
export interface DailyTimes {
  readonly type: "daily";
  /** Local wall-clock times as `"HH:MM"`. */
  readonly times: readonly string[];
}

/** `IntervalTimes(int Hours, DateTimeOffset StartAtUtc)` — every N hours. */
export interface IntervalTimes {
  readonly type: "interval";
  readonly hours: number;
  /** Unix ms of the first occurrence (C# `StartAtUtc`). */
  readonly startAt: number;
}

/** `WeeklyTimes(IReadOnlyList<DayOfWeek> DaysOfWeek, IReadOnlyList<TimeOnly> Times)`. */
export interface WeeklyTimes {
  readonly type: "weekly";
  readonly daysOfWeek: readonly Weekday[];
  /** Local wall-clock times as `"HH:MM"`, applied on each selected day. */
  readonly times: readonly string[];
}

/**
 * A single moment, and then never again.
 *
 * `at` is an instant, never a date with the time left off — see ADR 0006, which
 * records why an all-day Reminder is a future variant of its own rather than a
 * nullable field here.
 *
 * It has no C# counterpart: `ScheduleTimes.cs` had three subrecords and the
 * archive is frozen, so this variant and `NoneTimes` are the first parts of the
 * union this repository owns outright.
 */
export interface OnceTimes {
  readonly type: "once";
  /** Unix ms of the one moment this Reminder comes due. */
  readonly at: number;
}

/**
 * No schedule at all — the Backlog.
 *
 * A Reminder with this schedule produces no Occurrences and never appears on
 * the timeline; it is rendered from the Reminder row itself, and completing one
 * mints its Instance at the moment the person acts (ADR 0002).
 *
 * It replaces something that used to happen by accident. A `daily` schedule
 * with no times also produces nothing, which looks like a Backlog item but is
 * really a misconfiguration; after this variant the difference is expressible in
 * the type, and `createReminder`'s default of an empty `daily` schedule is the
 * one remaining place the two are still conflated.
 */
export interface NoneTimes {
  readonly type: "none";
}

export type ScheduleTimes =
  | DailyTimes
  | IntervalTimes
  | WeeklyTimes
  | OnceTimes
  | NoneTimes;

export interface DailyTimesWire {
  readonly $type: "DailyTimes";
  readonly Times: readonly string[];
}

export interface IntervalTimesWire {
  readonly $type: "IntervalTimes";
  readonly Hours: number;
  readonly StartAtUtc: string;
}

export interface WeeklyTimesWire {
  readonly $type: "WeeklyTimes";
  readonly DaysOfWeek: readonly number[];
  readonly Times: readonly string[];
}

/**
 * `OnceTimes` on the wire. `AtUtc` mirrors `IntervalTimesWire.StartAtUtc`: both
 * are instants, so both are written the way .NET writes a `DateTimeOffset`.
 */
export interface OnceTimesWire {
  readonly $type: "OnceTimes";
  readonly AtUtc: string;
}

/** `NoneTimes` on the wire — a discriminator with nothing under it. */
export interface NoneTimesWire {
  readonly $type: "NoneTimes";
}

export type ScheduleTimesWire =
  | DailyTimesWire
  | IntervalTimesWire
  | WeeklyTimesWire
  | OnceTimesWire
  | NoneTimesWire;

/**
 * Compile-time exhaustiveness guard for tagged unions.
 *
 * Reaching this in a `switch` means the compiler could not prove every variant
 * was handled, so the call site fails to build. The runtime throw only fires if
 * a value was forced past the type system (e.g. a hand-built object from JS).
 */
export function assertNever(value: never): never {
  throw new Error(
    `Unhandled discriminated-union member: ${JSON.stringify(value)}`
  );
}

export function dailyTimes(times: readonly string[]): DailyTimes {
  return { type: "daily", times: [...times] };
}

export function intervalTimes(hours: number, startAt: number): IntervalTimes {
  return { type: "interval", hours, startAt };
}

export function weeklyTimes(
  daysOfWeek: readonly Weekday[],
  times: readonly string[]
): WeeklyTimes {
  return { type: "weekly", daysOfWeek: [...daysOfWeek], times: [...times] };
}

export function onceTimes(at: number): OnceTimes {
  return { type: "once", at };
}

export function noneTimes(): NoneTimes {
  return { type: "none" };
}

/**
 * Whether a schedule carries what its own variant needs to produce Occurrences.
 *
 * The rule an entry surface asks before saving, kept here rather than in the
 * form that asks it: what makes a schedule usable is a property of the schedule,
 * and a second surface — the Backlog, which sets one too — would otherwise write
 * its own copy of the answer.
 *
 * `none` is well formed by definition; having no time is the whole point of it.
 * The rest need the field they are built around, and `daily`/`weekly` need at
 * least one usable moment: a `daily` schedule with no times produces nothing,
 * which is the misconfiguration `none` exists to replace.
 *
 * It is deliberately not a judgement about *when*. A `once` schedule whose
 * instant has passed is well formed and simply spent — that is the state every
 * one-off ends in, and it arrives that way from a pull.
 *
 * Throws for a variant it does not know, matching `nextOccurrences`: unreachable
 * from typed callers, and reachable from a JSON boundary.
 */
export function isWellFormedSchedule(schedule: ScheduleTimes): boolean {
  switch (schedule.type) {
    case "daily":
      return schedule.times.some(isUsableTime);
    case "weekly":
      return (
        schedule.daysOfWeek.length > 0 && schedule.times.some(isUsableTime)
      );
    case "interval":
      return schedule.hours > 0 && Number.isFinite(schedule.startAt);
    case "once":
      return Number.isFinite(schedule.at);
    case "none":
      return true;
    default:
      return assertNever(schedule);
  }
}

/** A `"HH:MM"` the Occurrence engine will accept. */
function isUsableTime(time: string): boolean {
  return parseTimeOnly(time) !== null;
}

/**
 * C#'s `DayOfWeek` ordinals. `Sunday` is 0 in both .NET and JavaScript, so the
 * ordinals transfer without a shift.
 */
const WEEKDAY_NAMES = [
  "Sunday",
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
] as const;

/**
 * `TimeOnly` on the wire is `HH:mm:ss`, optionally with fractional ticks. The
 * POC's schedule engine works in `HH:MM`, so both are accepted on the way in
 * and minutes precision is what the domain keeps.
 */
const TIME_ONLY_PATTERN =
  /^([0-9]{1,2}):([0-9]{2})(?::[0-9]{2}(?:\.[0-9]+)?)?$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isUnknownArray(value: unknown): value is readonly unknown[] {
  return Array.isArray(value);
}

function parseTimeOnly(value: unknown): string | null {
  if (typeof value !== "string") return null;

  const match = TIME_ONLY_PATTERN.exec(value);
  if (match === null) return null;

  const [, hourStr, minuteStr] = match;
  /* v8 ignore next */
  if (hourStr === undefined || minuteStr === undefined) return null;

  const hour = Number.parseInt(hourStr, 10);
  const minute = Number.parseInt(minuteStr, 10);
  if (hour > 23 || minute > 59) return null;

  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function parseTimes(value: unknown): readonly string[] | null {
  if (!isUnknownArray(value)) return null;

  const times: string[] = [];
  for (const entry of value) {
    const time = parseTimeOnly(entry);
    if (time === null) return null;
    times.push(time);
  }

  return times;
}

/**
 * Reads a `DayOfWeek`.
 *
 * Both encodings the archived C# produced are accepted, because it wrote two:
 * `TaskRx.Data/DapperInitializer.cs` persists with options that carry only the
 * `ScheduleTimes` converter, so `DayOfWeek` lands as an ordinal, while
 * `apps/TaskRx.Maui/ReminderApiClient.cs:14` adds a global
 * `JsonStringEnumConverter`, which turns the same field into a name over HTTP.
 */
function parseWeekday(value: unknown): Weekday | null {
  if (typeof value === "number") {
    if (!Number.isInteger(value) || value < 0 || value > 6) return null;
    return value as Weekday;
  }

  if (typeof value === "string") {
    const index = WEEKDAY_NAMES.findIndex(
      (name) => name.toLowerCase() === value.toLowerCase()
    );
    return index === -1 ? null : (index as Weekday);
  }

  return null;
}

function parseWeekdays(value: unknown): readonly Weekday[] | null {
  if (!isUnknownArray(value)) return null;

  const days: Weekday[] = [];
  for (const entry of value) {
    const day = parseWeekday(entry);
    if (day === null) return null;
    days.push(day);
  }

  return days;
}

function parseHours(value: unknown): number | null {
  if (typeof value !== "number") return null;
  if (!Number.isInteger(value) || value <= 0) return null;
  return value;
}

function parseInstant(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}

function toTimeOnly(time: string): string {
  const normalized = parseTimeOnly(time);
  if (normalized === null) {
    throw new Error(
      `Cannot serialize "${time}" as a TimeOnly: expected "HH:MM".`
    );
  }
  return `${normalized}:00`;
}

/**
 * Writes the shape `ScheduleTimesJsonConverter.Write` produces: a `$type`
 * discriminator naming the C# record, then that record's members under their
 * PascalCase names.
 *
 * Instants are written as `toISOString()` (`...Z`), which spells the same
 * instant .NET writes as `+00:00`; `parseScheduleTimes` reads either.
 *
 * Throws if a time cannot be represented as a `TimeOnly` — an invalid time has
 * no wire encoding, and silently emitting `"25:00:00"` would push the failure
 * into the consumer.
 */
export function serializeScheduleTimes(
  schedule: ScheduleTimes
): ScheduleTimesWire {
  switch (schedule.type) {
    case "daily":
      return {
        $type: "DailyTimes",
        Times: schedule.times.map(toTimeOnly),
      };
    case "interval":
      return {
        $type: "IntervalTimes",
        Hours: schedule.hours,
        StartAtUtc: new Date(schedule.startAt).toISOString(),
      };
    case "weekly":
      return {
        $type: "WeeklyTimes",
        DaysOfWeek: [...schedule.daysOfWeek],
        Times: schedule.times.map(toTimeOnly),
      };
    case "once":
      return {
        $type: "OnceTimes",
        AtUtc: new Date(schedule.at).toISOString(),
      };
    case "none":
      return { $type: "NoneTimes" };
    /* v8 ignore next 2 */
    default:
      return assertNever(schedule);
  }
}

/**
 * Reads the wire shape back into the domain union.
 *
 * Returns `null` for anything it cannot fully validate — an absent or unknown
 * `$type`, a non-object, or a payload whose members are missing or malformed.
 * `null` rather than a throw mirrors `ScheduleTimesJsonConverter.Read`, which
 * returns `null` for an unrecognized discriminator and lets the caller decide
 * on a default.
 */
export function parseScheduleTimes(value: unknown): ScheduleTimes | null {
  if (!isRecord(value)) return null;

  switch (value.$type) {
    case "DailyTimes": {
      const times = parseTimes(value.Times);
      return times === null ? null : dailyTimes(times);
    }
    case "IntervalTimes": {
      const hours = parseHours(value.Hours);
      const startAt = parseInstant(value.StartAtUtc);
      if (hours === null || startAt === null) return null;
      return intervalTimes(hours, startAt);
    }
    case "WeeklyTimes": {
      const daysOfWeek = parseWeekdays(value.DaysOfWeek);
      const times = parseTimes(value.Times);
      if (daysOfWeek === null || times === null) return null;
      return weeklyTimes(daysOfWeek, times);
    }
    case "OnceTimes": {
      const at = parseInstant(value.AtUtc);
      return at === null ? null : onceTimes(at);
    }
    case "NoneTimes":
      return noneTimes();
    default:
      return null;
  }
}
