import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Executable coverage for `rescheduleAll`'s cancel pass.
 *
 * This function had no test at all, which is how it shipped cancelling every
 * pending snooze. The mechanism is worth stating plainly, because nothing in
 * the function's own body looks wrong: `rescheduleAll` computes the set of
 * alarms a schedule *should* have, then cancels every Notifee trigger outside
 * that set. A snooze is armed by the user rather than derived from a schedule,
 * so it is never in the desired set — and was therefore swept up as stale.
 * `_layout.tsx` calls `rescheduleAll` on every transition to `active`, so
 * snoozing a dose and switching apps once was enough to lose the alarm, with
 * no error and no status change.
 *
 * The tests pair deliberately. "Preserves a snooze" alone would pass a
 * `rescheduleAll` that never cancels anything, so "still cancels a stale
 * occurrence" runs beside it and fails that over-correction. Idempotence is
 * todo 7's own acceptance criterion (plan `:157`), which had never been
 * executed either.
 *
 * Everything below Notifee is mocked. The subject is which ids the cancel pass
 * selects, and a real notifee would pull react-native into a node test.
 *
 * ## Why these assert on calls, which the spec normally forbids
 *
 * `docs/specs/fused-timeline.md` § "What makes a good test here" rules out
 * asserting "which collaborator was called", and every case below does exactly
 * that. It is the documented exception the same spec grants — "notification
 * delivery… cannot be tested without an operating system" — and it is narrower
 * than it looks: arming an alarm *is* the observable behaviour here. These
 * functions return nothing, write nothing, and change no state a test can read;
 * the only thing that happens is the call. There is no return value to prefer.
 *
 * Two consequences are honoured deliberately. Cases stay phrased as outcomes
 * ("leaves a pending snooze armed") rather than as call transcripts. And no rule
 * that *can* be executed in the shared package is re-pinned here: ADR 0003's
 * "a task never displaces a dose from a full Horizon" belongs to
 * `horizon.test.ts`, where it runs against a pure function. What the Tier cases
 * below add is the wiring — that this module asks `selectMedicationHorizon` and
 * `entersMedicationHorizon` at all, which no pure test can see.
 */

/**
 * The two Notifee writers are typed with their real parameter shapes so
 * `.mock.calls` destructures into tuples rather than `[]`. That is what lets
 * the assertions below read `notification.id` and `trigger.alarmManager.type`
 * without a cast, and it means a change to either shape surfaces here as a type
 * error instead of an `undefined` at runtime.
 */
const mocks = vi.hoisted(() => ({
  getTriggerNotificationIds: vi.fn<() => Promise<string[]>>(),
  cancelTriggerNotifications: vi.fn<(ids: string[]) => Promise<void>>(),
  createTriggerNotification:
    vi.fn<
      (
        notification: { id: string },
        trigger: { timestamp: number; alarmManager: { type: string } }
      ) => Promise<void>
    >(),
  list: vi.fn(),
  get: vi.fn(),
}));

vi.mock("react-native-notify-kit", () => ({
  default: {
    getTriggerNotificationIds: mocks.getTriggerNotificationIds,
    cancelTriggerNotifications: mocks.cancelTriggerNotifications,
    createTriggerNotification: mocks.createTriggerNotification,
    createChannel: vi.fn(),
  },
  AlarmType: {
    SET_ALARM_CLOCK: "SET_ALARM_CLOCK",
    SET_EXACT_AND_ALLOW_WHILE_IDLE: "SET_EXACT_AND_ALLOW_WHILE_IDLE",
  },
  AndroidImportance: { HIGH: 4 },
  TriggerType: { TIMESTAMP: 0 },
}));

vi.mock("../db/repos/reminders", () => ({
  list: mocks.list,
  get: mocks.get,
}));

const { buildNotificationId, rescheduleAll, SNOOZE_NOTIFICATION_ID_PREFIX } =
  await import("./scheduler");
const { snoozeReminder } = await import("./snooze");

const USER = "user_owner";
/** 2026-07-29T12:00:00Z, fixed so occurrence ids are computed, not guessed. */
const NOW = Date.UTC(2026, 6, 29, 12, 0, 0);
/**
 * The instant a snooze defers to, decided by the caller.
 *
 * Deliberately NOT `NOW + 15 * 60_000`. The clock is frozen at `NOW` in these
 * tests, so a `snoozeReminder` that ignored this argument and recomputed the
 * instant from `Date.now()` would land on exactly the same number and the case
 * below would pass while asserting nothing. An offset no recomputation would
 * arrive at is what gives that assertion teeth.
 */
const SNOOZE_UNTIL = NOW + 37 * 60_000 + 1_234;

/** One active daily reminder at 08:00 — enough to produce a desired set. */
const REMINDER = {
  id: "rem-metformin",
  userId: USER,
  kind: "medication",
  name: "Metformin",
  schedule: { type: "daily", times: ["08:00"] },
  priority: "normal",
  tags: [],
  caregiverIds: [],
  active: true,
  createdAt: NOW,
  updatedAt: NOW,
};

/** The ids `rescheduleAll` decided to cancel, flattened across its calls. */
function cancelledIds(): string[] {
  return mocks.cancelTriggerNotifications.mock.calls.flatMap(([ids]) => ids);
}

/** The ids `rescheduleAll` decided to create. */
function createdIds(): string[] {
  return mocks.createTriggerNotification.mock.calls.map(
    ([notification]) => notification.id
  );
}

/**
 * The single id just created, or a thrown error naming what went wrong.
 *
 * Returning `string | undefined` here would let a test that armed nothing still
 * feed `undefined` into the next step and pass vacuously.
 */
function onlyCreatedId(): string {
  const [first, ...rest] = createdIds();
  if (first === undefined) {
    throw new Error("expected a notification to have been created, got none");
  }
  if (rest.length > 0) {
    throw new Error(
      `expected exactly one notification, got ${String(rest.length + 1)}`
    );
  }
  return first;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  mocks.list.mockResolvedValue([REMINDER]);
  mocks.get.mockResolvedValue(REMINDER);
  mocks.cancelTriggerNotifications.mockResolvedValue(undefined);
  mocks.createTriggerNotification.mockResolvedValue(undefined);
  mocks.getTriggerNotificationIds.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("rescheduleAll cancel pass", () => {
  it("leaves a pending snooze armed", async () => {
    // The exact id `snoozeReminder` would have written, built by the real code
    // rather than hand-spelled, so a change to the id format cannot make this
    // test quietly stop describing a real snooze.
    await snoozeReminder("rem-metformin", NOW - 3_600_000, SNOOZE_UNTIL);
    const snoozeId = onlyCreatedId();
    expect(snoozeId).toMatch(/^snooze-/);

    mocks.createTriggerNotification.mockClear();
    mocks.getTriggerNotificationIds.mockResolvedValue([snoozeId]);

    await rescheduleAll(USER);

    expect(cancelledIds()).not.toContain(snoozeId);
  });

  it("still cancels an occurrence alarm that is no longer scheduled", async () => {
    // A real occurrence id for a reminder that is no longer in the desired set
    // — the thing the cancel pass exists to remove. Without this, a
    // `rescheduleAll` that simply never cancels would pass the test above.
    const staleId = buildNotificationId("rem-discontinued", NOW + 60_000);
    mocks.getTriggerNotificationIds.mockResolvedValue([staleId]);

    await rescheduleAll(USER);

    expect(cancelledIds()).toContain(staleId);
  });

  it("distinguishes the two within a single pass", async () => {
    await snoozeReminder("rem-metformin", NOW - 3_600_000, SNOOZE_UNTIL);
    const snoozeId = onlyCreatedId();
    mocks.createTriggerNotification.mockClear();

    const staleId = buildNotificationId("rem-discontinued", NOW + 60_000);
    mocks.getTriggerNotificationIds.mockResolvedValue([snoozeId, staleId]);

    await rescheduleAll(USER);

    expect(cancelledIds()).toEqual([staleId]);
  });

  it("does not treat a reminder whose id merely starts with the marker as a snooze", async () => {
    // The exclusion is a prefix test on the whole id, and a reminder id is
    // free-form text. A reminder literally named with the marker must still be
    // cancellable, or the escape hatch becomes a way to strand alarms.
    const lookalike = buildNotificationId(
      `${SNOOZE_NOTIFICATION_ID_PREFIX}rem-x`,
      NOW + 60_000
    );
    mocks.getTriggerNotificationIds.mockResolvedValue([lookalike]);

    await rescheduleAll(USER);

    // Documents the known limitation rather than asserting a fix: the prefix
    // test cannot tell this apart from a real snooze. It is unreachable today
    // because ids are generated UUIDs, and is recorded here so a future move to
    // user-authored ids finds a failing test instead of a silent leak.
    expect(cancelledIds()).not.toContain(lookalike);
  });
});

describe("rescheduleAll idempotence", () => {
  it("cancels and creates nothing on a second run with unchanged data", async () => {
    await rescheduleAll(USER);
    const firstRun = createdIds();
    expect(firstRun.length).toBeGreaterThan(0);

    // The device now holds exactly what the first run armed.
    mocks.getTriggerNotificationIds.mockResolvedValue(firstRun);
    mocks.createTriggerNotification.mockClear();
    mocks.cancelTriggerNotifications.mockClear();

    await rescheduleAll(USER);

    expect(createdIds()).toEqual([]);
    expect(cancelledIds()).toEqual([]);
  });
});

describe("snoozeReminder alarm primitive", () => {
  it("arms SET_ALARM_CLOCK, the primitive the scheduler uses", async () => {
    await snoozeReminder("rem-metformin", NOW - 3_600_000, SNOOZE_UNTIL);

    const [call] = mocks.createTriggerNotification.mock.calls;
    if (call === undefined) {
      throw new Error("snoozeReminder armed no notification at all");
    }
    const [, trigger] = call;
    expect(trigger.alarmManager.type).toBe("SET_ALARM_CLOCK");
  });
});

/**
 * ADR 0003, at the two places this module can arm an alarm.
 *
 * Every case is stated as "nothing was armed" rather than "the right thing was
 * armed", because the guarantee is an absence: a task must not reach an
 * alarm-clock primitive or the bounded Horizon, whatever else changes about how
 * medications are scheduled.
 *
 * The rule that a task cannot *displace* a dose from a full Horizon is not here.
 * It is a property of `selectMedicationHorizon`, it runs against that pure
 * function in `packages/shared/src/schedule/horizon.test.ts`, and re-pinning it
 * through mocks would state one rule twice in the weaker of the two mediums.
 * What is left here is only what a pure test cannot see: that this module asks
 * the Tier question at all.
 */
describe("the medication Tier admits no tasks", () => {
  const TASK = { ...REMINDER, id: "rem-pharmacy", kind: "task" };

  it("arms nothing when a task is snoozed, and says it armed nothing", async () => {
    mocks.get.mockResolvedValue(TASK);

    const armed = await snoozeReminder(
      "rem-pharmacy",
      NOW - 3_600_000,
      SNOOZE_UNTIL
    );

    expect(armed).toBe(false);
    expect(createdIds()).toEqual([]);
  });

  it("arms nothing when the reminder is not on this device", async () => {
    // A row that is absent is a row not known to be a medication. Reachable
    // from a stale deep link naming a task this device has not pulled, or one
    // just deleted.
    mocks.get.mockResolvedValue(null);

    const armed = await snoozeReminder(
      "rem-unknown",
      NOW - 3_600_000,
      SNOOZE_UNTIL
    );

    expect(armed).toBe(false);
    expect(createdIds()).toEqual([]);
  });

  it("still arms a snooze for a medication", async () => {
    // Beside the two above, so "arms nothing, ever" cannot pass for a fix.
    const armed = await snoozeReminder(
      "rem-metformin",
      NOW - 3_600_000,
      SNOOZE_UNTIL
    );

    expect(armed).toBe(true);
    expect(createdIds()).toHaveLength(1);
  });

  it("arms the snooze at the instant the caller decided, not one of its own", async () => {
    // The Instance the caller has already written names `SNOOZE_UNTIL`. A
    // second reading of the clock in here would arm a different moment and
    // build a different id from it.
    await snoozeReminder("rem-metformin", NOW - 3_600_000, SNOOZE_UNTIL);

    const [call] = mocks.createTriggerNotification.mock.calls;
    if (call === undefined) {
      throw new Error("snoozeReminder armed no notification at all");
    }
    const [notification, trigger] = call;
    expect(trigger.timestamp).toBe(SNOOZE_UNTIL);
    expect(notification.id).toContain(String(SNOOZE_UNTIL));
  });

  it("leaves a task out of the alarms rescheduleAll arms", async () => {
    mocks.list.mockResolvedValue([REMINDER, TASK]);

    await rescheduleAll(USER);

    expect(createdIds().length).toBeGreaterThan(0);
    expect(createdIds().every((id) => id.startsWith("rem-metformin-"))).toBe(
      true
    );
  });
});
