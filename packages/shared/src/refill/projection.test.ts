import { afterAll, beforeAll, describe, expect, it } from "vitest";

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import type { ScheduleTimes } from "../domain/schedule-times";
import {
  dailyTimes,
  intervalTimes,
  noneTimes,
  weeklyTimes,
} from "../domain/schedule-times";

import type { RefillOutlook } from "./projection";
import { DEFAULT_REFILL_REMINDER_DAYS, projectRefill } from "./projection";

/** Local wall-clock helper, matching the pattern in `occurrences.test.ts`. */
function local(
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0
): number {
  return new Date(year, month - 1, day, hour, minute, 0, 0).getTime();
}

/** An hour before the first dose of the day, so today's dose is still to come. */
const NOW = local(2026, 8, 13, 8);

const ONCE_A_DAY = dailyTimes(["09:00"]);

function buildMedication(options: {
  readonly schedule?: ScheduleTimes;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly kind?: Reminder["kind"];
  readonly active?: boolean;
}): Reminder {
  return createReminder({
    id: "med-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: options.kind ?? "medication",
    name: "Atorvastatin",
    schedule: options.schedule ?? ONCE_A_DAY,
    ...(options.pillsRemaining === undefined
      ? {}
      : { pillsRemaining: options.pillsRemaining }),
    ...(options.pillsPerDose === undefined
      ? {}
      : { pillsPerDose: options.pillsPerDose }),
    ...(options.refillReminderDays === undefined
      ? {}
      : { refillReminderDays: options.refillReminderDays }),
    ...(options.active === undefined ? {} : { active: options.active }),
  });
}

/**
 * Narrows to the projected variant so a case can read its fields.
 *
 * A failed narrowing is a failed assertion rather than a thrown `undefined`
 * three lines later, which is what makes the "why is there no projection"
 * cases readable when one of them regresses.
 */
function outlook(reminder: Reminder, now: number = NOW): RefillOutlook {
  const projection = projectRefill(reminder, now);
  if (!projection.projected) {
    throw new Error(`expected a projection, got reason ${projection.reason}`);
  }
  return projection;
}

/**
 * How long a finite supply lasts.
 *
 * The rule these cases state, and the one `planRefill`'s ported threshold could
 * not: the supply is spent an Occurrence at a time, and each Occurrence costs
 * `pillsPerDose` — so what matters is the Schedule the Reminder actually keeps,
 * not the number of pills alone.
 */
describe("projectRefill — how long the supply lasts", () => {
  it("spends one dose per Occurrence, so thirty daily pills last thirty days", () => {
    const med = buildMedication({ pillsRemaining: 30, pillsPerDose: 1 });

    expect(outlook(med).occurrencesRemaining).toBe(30);
    expect(outlook(med).daysRemaining).toBe(30);
  });

  it("counts what is taken each time, so the same thirty taken two at a time last half as long", () => {
    const med = buildMedication({ pillsRemaining: 30, pillsPerDose: 2 });

    expect(outlook(med).occurrencesRemaining).toBe(15);
    expect(outlook(med).daysRemaining).toBe(15);
  });

  it("counts how often they are taken, so twice a day halves the days again", () => {
    const med = buildMedication({
      schedule: dailyTimes(["09:00", "21:00"]),
      pillsRemaining: 30,
      pillsPerDose: 1,
    });

    expect(outlook(med).occurrencesRemaining).toBe(30);
    expect(outlook(med).daysRemaining).toBe(15);
  });

  it("follows a weekly schedule's own days rather than assuming a daily one", () => {
    // Thursday 2026-08-13. Mondays, Wednesdays and Fridays: six doses reach the
    // Wednesday a fortnight out, and the seventh — the one there is nothing for
    // — is the Friday after. Six daily pills would have run out on the 19th.
    const med = buildMedication({
      schedule: weeklyTimes([1, 3, 5], ["09:00"]),
      pillsRemaining: 6,
      pillsPerDose: 1,
    });

    expect(outlook(med).runsOutAt).toBe(local(2026, 8, 28, 9));
  });

  it("does not count a leftover pill that cannot make a whole dose", () => {
    const med = buildMedication({ pillsRemaining: 5, pillsPerDose: 2 });

    expect(outlook(med).occurrencesRemaining).toBe(2);
  });

  it("runs out at the first Occurrence the supply cannot cover", () => {
    const med = buildMedication({ pillsRemaining: 3, pillsPerDose: 1 });

    // Today's, tomorrow's and the next are covered; the fourth is not.
    expect(outlook(med).runsOutAt).toBe(local(2026, 8, 16, 9));
    expect(outlook(med).daysRemaining).toBe(3);
  });

  it("says a supply already spent runs out at the very next dose", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    expect(outlook(med).occurrencesRemaining).toBe(0);
    expect(outlook(med).runsOutAt).toBe(local(2026, 8, 13, 9));
    expect(outlook(med).daysRemaining).toBe(0);
  });

  it("treats a supply recorded as less than nothing as nothing", () => {
    const med = buildMedication({ pillsRemaining: -5, pillsPerDose: 1 });

    expect(outlook(med).occurrencesRemaining).toBe(0);
  });

  /**
   * A Reminder with no Schedule produces no Occurrences at all, so its supply
   * is never spent. Reporting a run-out date for it would be inventing one.
   */
  it("has no run-out date for a medication that never comes due", () => {
    const med = buildMedication({
      schedule: noneTimes(),
      pillsRemaining: 1,
      pillsPerDose: 1,
    });

    expect(outlook(med).runsOutAt).toBeUndefined();
    expect(outlook(med).daysRemaining).toBeUndefined();
    expect(outlook(med).needsRefill).toBe(false);
  });

  it("reports no date rather than a wrong one when the supply outlasts the schedule horizon", () => {
    // The daily engine walks 60 days; two hundred doses are well past it.
    const med = buildMedication({ pillsRemaining: 200, pillsPerDose: 1 });

    expect(outlook(med).occurrencesRemaining).toBe(200);
    expect(outlook(med).runsOutAt).toBeUndefined();
    expect(outlook(med).needsRefill).toBe(false);
  });

  /**
   * `interval` schedules have no horizon of their own — they return exactly as
   * many Occurrences as they are asked for — so the projection has to stop
   * itself. It stops by answering "further off than I can see", never by
   * answering a nearer date than the truth.
   */
  it("stops walking an endless schedule rather than projecting a year of hourly doses", () => {
    const med = buildMedication({
      schedule: intervalTimes(1, NOW),
      pillsRemaining: 5000,
      pillsPerDose: 1,
    });

    expect(outlook(med).runsOutAt).toBeUndefined();
  });

  it("still projects an interval supply that is within reach", () => {
    const med = buildMedication({
      schedule: intervalTimes(6, NOW),
      pillsRemaining: 12,
      pillsPerDose: 1,
    });

    // Twelve doses six hours apart: the thirteenth is three days out.
    expect(outlook(med).daysRemaining).toBe(3);
  });
});

/**
 * When the warning is raised.
 *
 * The window is the Reminder's own — the acceptance criterion says the refill is
 * raised "according to the warning window on that Reminder" — so these cases
 * move the window and the supply independently and assert the verdict flips
 * where the two meet.
 */
describe("projectRefill — the warning window", () => {
  it("raises a refill when the supply runs out inside the window", () => {
    const med = buildMedication({
      pillsRemaining: 7,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(outlook(med).needsRefill).toBe(true);
  });

  it("does not raise one while the supply outlasts the window", () => {
    const med = buildMedication({
      pillsRemaining: 8,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(outlook(med).needsRefill).toBe(false);
  });

  it("raises earlier for someone who asked for more warning", () => {
    const med = buildMedication({
      pillsRemaining: 8,
      pillsPerDose: 1,
      refillReminderDays: 14,
    });

    expect(outlook(med).needsRefill).toBe(true);
  });

  it("raises on the strength of the doses left, not the pills left", () => {
    // Sixteen pills is twice the eight that would sit outside a seven-day
    // window — but taken two at a time they are eight doses, so they do not.
    const med = buildMedication({
      pillsRemaining: 16,
      pillsPerDose: 2,
      refillReminderDays: 7,
    });

    expect(outlook(med).needsRefill).toBe(false);

    const takenFour = buildMedication({
      pillsRemaining: 16,
      pillsPerDose: 4,
      refillReminderDays: 7,
    });

    expect(outlook(takenFour).needsRefill).toBe(true);
  });

  it("gives a Reminder that named no window the default one", () => {
    const med = buildMedication({ pillsRemaining: 7, pillsPerDose: 1 });

    expect(outlook(med).warningWindowDays).toBe(DEFAULT_REFILL_REMINDER_DAYS);
    expect(outlook(med).needsRefill).toBe(true);
  });

  it("reports the window it judged against, so the surface can say so", () => {
    const med = buildMedication({
      pillsRemaining: 30,
      pillsPerDose: 1,
      refillReminderDays: 3,
    });

    expect(outlook(med).warningWindowDays).toBe(3);
  });

  it("honours a window of nothing by warning only on the day itself", () => {
    const runsOutToday = buildMedication({
      pillsRemaining: 0,
      pillsPerDose: 1,
      refillReminderDays: 0,
    });
    const runsOutTomorrow = buildMedication({
      pillsRemaining: 1,
      pillsPerDose: 1,
      refillReminderDays: 0,
    });

    expect(outlook(runsOutToday).needsRefill).toBe(true);
    expect(outlook(runsOutTomorrow).needsRefill).toBe(false);
  });
});

/**
 * What has no projection, and why each is a different answer.
 *
 * The acceptance criterion is that a medication with no recorded supply shows
 * "no refill information rather than a misleading zero" — so the absence has to
 * be a value the surface can render as a sentence, not a `0` it cannot tell
 * apart from an empty bottle.
 */
describe("projectRefill — what cannot be projected", () => {
  it("has nothing to say about a task", () => {
    const task = buildMedication({
      kind: "task",
      pillsRemaining: 5,
      pillsPerDose: 1,
    });

    expect(projectRefill(task, NOW)).toEqual({
      projected: false,
      reason: "not-a-medication",
    });
  });

  it("has nothing to say about a medication that was stopped", () => {
    const med = buildMedication({
      pillsRemaining: 1,
      pillsPerDose: 1,
      active: false,
    });

    expect(projectRefill(med, NOW)).toEqual({
      projected: false,
      reason: "stopped",
    });
  });

  it("distinguishes an unrecorded supply from an empty one", () => {
    const unrecorded = buildMedication({ pillsPerDose: 1 });

    expect(projectRefill(unrecorded, NOW)).toEqual({
      projected: false,
      reason: "no-supply",
    });

    const empty = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    expect(outlook(empty).occurrencesRemaining).toBe(0);
  });

  it("cannot project a supply without knowing how many are taken at a time", () => {
    const med = buildMedication({ pillsRemaining: 30 });

    expect(projectRefill(med, NOW)).toEqual({
      projected: false,
      reason: "no-dose-size",
    });
  });

  it("treats a dose of zero pills as no dose size at all", () => {
    // Otherwise the division is an infinite supply, and a medication that never
    // runs out is the one reading this feature exists to prevent.
    const med = buildMedication({ pillsRemaining: 30, pillsPerDose: 0 });

    expect(projectRefill(med, NOW)).toEqual({
      projected: false,
      reason: "no-dose-size",
    });
  });
});

/**
 * Days remaining is a count of local calendar days, and the suite otherwise runs
 * at `TZ=UTC` where every day is 24 hours. A projection built on fixed 24-hour
 * blocks is a day out either side of a clock change — which is a refill warning
 * that arrives the day after the pills ran out.
 */
describe("projectRefill — daylight saving", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("counts three days across spring forward, not two", () => {
    // 2027-03-14 is the US spring-forward date.
    const med = buildMedication({ pillsRemaining: 3, pillsPerDose: 1 });

    expect(outlook(med, local(2027, 3, 13, 8)).daysRemaining).toBe(3);
  });

  it("counts three days across fall back, not four", () => {
    // 2027-11-07 is the US fall-back date.
    const med = buildMedication({ pillsRemaining: 3, pillsPerDose: 1 });

    expect(outlook(med, local(2027, 11, 6, 8)).daysRemaining).toBe(3);
  });
});
