import type { Reminder, ScheduleTimes } from "@taskrx/shared";
import {
  createReminder,
  dailyTimes,
  noneTimes,
  projectRefill,
} from "@taskrx/shared";
import { describe, expect, it } from "vitest";

import { formatDay } from "./datetime";
import { refillDetail, refillHeadline, refillUrgency } from "./refill";

/**
 * What the Refill surface says, given what the projection found.
 *
 * These run against real `projectRefill` output rather than hand-built objects,
 * so a case cannot state a combination the projection never produces — "no run
 * out date but plenty of days left", say. The rule each one pins is a sentence a
 * person reads about their own medication, and on this surface the wrong
 * sentence over the right number is the failure that matters.
 */

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

/** An hour before the first dose of the day, so today's is still to come. */
const NOW = local(2026, 8, 13, 8);

function medication(options: {
  readonly schedule?: ScheduleTimes;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
}): Reminder {
  return createReminder({
    id: "med-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: "medication",
    name: "Atorvastatin",
    schedule: options.schedule ?? dailyTimes(["09:00"]),
    ...(options.pillsRemaining === undefined
      ? {}
      : { pillsRemaining: options.pillsRemaining }),
    ...(options.pillsPerDose === undefined
      ? {}
      : { pillsPerDose: options.pillsPerDose }),
    ...(options.refillReminderDays === undefined
      ? {}
      : { refillReminderDays: options.refillReminderDays }),
  });
}

function say(med: Reminder): {
  readonly headline: string;
  readonly detail: string;
} {
  const projection = projectRefill(med, NOW);
  return {
    headline: refillHeadline(med, projection),
    detail: refillDetail(med, projection),
  };
}

describe("refillHeadline", () => {
  it("counts the days when there are days to count", () => {
    expect(
      say(medication({ pillsRemaining: 12, pillsPerDose: 1 })).headline
    ).toBe("12 days left");
  });

  it("says one day rather than 1 days", () => {
    expect(
      say(medication({ pillsRemaining: 1, pillsPerDose: 1 })).headline
    ).toBe("1 day left");
  });

  it("says today for a supply already spent", () => {
    expect(
      say(medication({ pillsRemaining: 0, pillsPerDose: 1 })).headline
    ).toBe("Runs out today");
  });

  /**
   * The fourth acceptance criterion: "a medication with no recorded supply shows
   * no refill information rather than a misleading zero". "0 days left" and
   * "Supply not tracked" are opposite facts, and on this surface the wrong one
   * is an alarm about a medication that is fine.
   */
  it("never turns an unrecorded supply into a number", () => {
    const unrecorded = say(medication({ pillsPerDose: 1 }));

    expect(unrecorded.headline).toBe("Supply not tracked");
    expect(unrecorded.headline).not.toMatch(/\d/);
    expect(unrecorded.detail).toBe(
      "Add how many you have to see how long it lasts."
    );
  });

  it("asks for the dose size when that is the missing half", () => {
    expect(say(medication({ pillsRemaining: 30 })).detail).toBe(
      "Add how many you take at a time to see how long it lasts."
    );
  });

  it("calls a supply beyond the walk plenty", () => {
    // Two hundred daily doses are past the schedule engine's 60-day walk.
    expect(
      say(medication({ pillsRemaining: 200, pillsPerDose: 1 })).headline
    ).toBe("Plenty left");
  });

  /**
   * The same missing date, the opposite news. A Reminder with no times set has
   * no run-out date because nothing is being taken — calling an empty bottle
   * "Plenty left" would be the misleading reading arriving by another route.
   */
  it("does not call an empty bottle plenty just because nothing is scheduled", () => {
    const unscheduled = say(
      medication({ schedule: noneTimes(), pillsRemaining: 0, pillsPerDose: 1 })
    );

    expect(unscheduled.headline).toBe("No times set");
    expect(unscheduled.detail).toBe(
      "nothing left, and no times set — so none are being used up"
    );
  });
});

describe("refillDetail", () => {
  /**
   * `CONTEXT.md` defines a Dose as the quantity taken at one time and says to
   * avoid using the word for a scheduled taking — which is exactly what a count
   * of Occurrences is. The verb carries it without the noun, the way the
   * adherence headline became "14 of 18 taken".
   */
  it("counts what is left without calling them doses", () => {
    const detail = say(
      medication({ pillsRemaining: 12, pillsPerDose: 1 })
    ).detail;

    expect(detail).toMatch(/^enough for 12 more, runs out /);
    expect(detail).not.toMatch(/dose/i);
  });

  it("says one more rather than 1 mores", () => {
    expect(
      say(medication({ pillsRemaining: 1, pillsPerDose: 1 })).detail
    ).toMatch(/^enough for 1 more, runs out /);
  });

  it("shows the working, so a changed dose size can be checked against it", () => {
    // Thirty pills taken three at a time is ten takings, not thirty.
    expect(
      say(medication({ pillsRemaining: 30, pillsPerDose: 3 })).detail
    ).toMatch(/^enough for 10 more, /);
  });

  it("names the day the supply runs out", () => {
    expect(say(medication({ pillsRemaining: 3, pillsPerDose: 1 })).detail).toBe(
      `enough for 3 more, runs out ${formatDay(local(2026, 8, 16, 9))}`
    );
  });

  /**
   * An empty bottle has not "run out on" a future date. The date is when the
   * next one is wanted, which is the sentence that says what to do about it.
   */
  it("tells an empty bottle when the next one is due, not when it runs out", () => {
    expect(say(medication({ pillsRemaining: 0, pillsPerDose: 1 })).detail).toBe(
      `nothing left — the next is due ${formatDay(local(2026, 8, 13, 9))}`
    );
  });
});

describe("refillUrgency", () => {
  it("says nothing about a medication that is fine", () => {
    const comfortable = medication({
      pillsRemaining: 40,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(refillUrgency(projectRefill(comfortable, NOW))).toBe("none");
  });

  it("says nothing about a supply nobody recorded", () => {
    expect(refillUrgency(projectRefill(medication({}), NOW))).toBe("none");
  });

  it("asks for a reorder while there is still something left", () => {
    const low = medication({
      pillsRemaining: 3,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(refillUrgency(projectRefill(low, NOW))).toBe("reorder");
  });

  /**
   * Louder only once there is genuinely none left. Reserving the strongest word
   * for the empty bottle is what keeps it meaning something on a list where
   * several medications are due a reorder.
   */
  it("is louder once there is none left at all", () => {
    const empty = medication({
      pillsRemaining: 0,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(refillUrgency(projectRefill(empty, NOW))).toBe("out");
  });
});
