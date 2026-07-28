import { describe, expect, it } from "vitest";

import type { Reminder } from "../domain/index";
import { dailyTimes, intervalTimes, weeklyTimes } from "../domain/index";

import type {
  ParseReminderOptions,
  ReminderParseResult,
} from "./reminder-parser";
import { parseReminder } from "./reminder-parser";

/**
 * The parser is pure, so the identity and the clock it would otherwise have
 * reached for are supplied by the caller. Fixed values here keep the expected
 * `IntervalTimes.startAt` readable in failure output.
 */
const NOW = Date.UTC(2026, 0, 15, 12, 0, 0);

const OPTIONS: ParseReminderOptions = {
  id: "11111111-1111-1111-1111-111111111111",
  userId: "user-1",
  now: NOW,
};

function parse(input: string): ReminderParseResult {
  return parseReminder(input, OPTIONS);
}

/**
 * Narrows `reminder` without a non-null assertion, which is banned here. A
 * throw rather than an `expect` keeps the rest of the test statically typed.
 */
function expectReminder(result: ReminderParseResult): Reminder {
  const { reminder } = result;
  if (reminder === undefined) {
    throw new Error(
      `Expected a reminder, got errors: [${result.errors.join(", ")}]`
    );
  }
  return reminder;
}

/**
 * The ten cases translated from `ReminderParserTests.cs` on the
 * `archive/dotnet-maui` branch, in source order.
 */
describe("parseReminder (ported xUnit cases)", () => {
  it("parses a medication with a named time and instructions", () => {
    const result = parse("atorvastatin 20mg every morning with food");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("atorvastatin");
    expect(reminder.dosage).toBe("20mg");
    expect(reminder.instructions).toBe("with food");
    expect(reminder.priority).toBe("normal");
    expect(reminder.schedule).toEqual(dailyTimes(["08:00"]));
  });

  it("parses twice daily as 08:00 and 18:00", () => {
    const result = parse("metformin 500mg twice daily");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("metformin");
    expect(reminder.dosage).toBe("500mg");
    expect(reminder.schedule).toEqual(dailyTimes(["08:00", "18:00"]));
  });

  it("parses every N hours as an interval schedule", () => {
    const result = parse("aspirin 81mg every 8 hours");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("aspirin");
    expect(reminder.dosage).toBe("81mg");
    expect(reminder.schedule).toEqual(intervalTimes(8, NOW));
  });

  it("parses a task with weekly days and a time", () => {
    const result = parse("do laundry every monday wednesday friday at 9am");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("task");
    expect(reminder.name).toBe("do laundry");
    expect(reminder.schedule).toEqual(weeklyTimes([1, 3, 5], ["09:00"]));
  });

  it("reports an error for empty input", () => {
    const result = parse("");

    expect(result.reminder).toBeUndefined();
    expect(result.errors).not.toEqual([]);
  });

  it("collects an unmatched token instead of failing", () => {
    const result = parse("atorvastatin 20mg frobnicate");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toContain("frobnicate");
    expect(reminder.name).toBe("atorvastatin");
  });

  it("parses urgent as critical priority", () => {
    const result = parse("urgent lisinopril 10mg daily");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.priority).toBe("critical");
    expect(reminder.name).toBe("lisinopril");
    expect(reminder.dosage).toBe("10mg");
    expect(reminder.schedule).toEqual(dailyTimes(["09:00"]));
  });

  it("parses the inventory and refill fields", () => {
    const result = parse(
      "atorvastatin 20mg daily 30 pills 1 per dose refill in 7 days"
    );
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("atorvastatin");
    expect(reminder.dosage).toBe("20mg");
    expect(reminder.pillsRemaining).toBe(30);
    expect(reminder.pillsPerDose).toBe(1);
    expect(reminder.refillReminderDays).toBe(7);
  });

  it("detects a medication from a drug suffix without a dosage", () => {
    const result = parse("atorvastatin daily");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("atorvastatin");
    expect(reminder.dosage).toBeUndefined();
    expect(reminder.schedule).toEqual(dailyTimes(["09:00"]));
  });

  it("keeps a leading task verb in the name", () => {
    const result = parse("take out trash weekly");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual([]);
    expect(reminder.kind).toBe("task");
    expect(reminder.name).toBe("take out trash");
    expect(reminder.schedule).toEqual(weeklyTimes([], []));
  });
});

describe("parseReminder README example", () => {
  it("parses the documented happy path end to end", () => {
    const result = parse("atorvastatin 20mg every morning with food");
    const reminder = expectReminder(result);

    expect(reminder.kind).toBe("medication");
    expect(reminder.dosage).toBe("20mg");
    expect(reminder.schedule).toEqual(dailyTimes(["08:00"]));
    expect(reminder.instructions).toBe("with food");
  });

  it("takes its identity and timestamps from the caller", () => {
    const reminder = expectReminder(parse("atorvastatin 20mg daily"));

    expect(reminder.id).toBe(OPTIONS.id);
    expect(reminder.userId).toBe(OPTIONS.userId);
    expect(reminder.createdAt).toBe(NOW);
    expect(reminder.updatedAt).toBe(NOW);
    expect(reminder.active).toBe(true);
    expect(reminder.tags).toEqual([]);
    expect(reminder.caregiverIds).toEqual([]);
  });
});

describe("parseReminder garbled input", () => {
  it("never rejects the whole input", () => {
    const result = parse("atorvastatin 20mg ###!!! frobnicate qwerty $$$");
    const reminder = expectReminder(result);

    expect(result.errors).toEqual([]);
    expect(result.unparsedTokens).toEqual(["frobnicate", "qwerty"]);
    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("atorvastatin");
    expect(reminder.dosage).toBe("20mg");
    expect(reminder.schedule).toEqual(dailyTimes(["09:00"]));
  });

  it("does not throw on punctuation-only input", () => {
    expect(() => parse("!!! ??? ***")).not.toThrow();
    expect(parse("!!! ??? ***").reminder).toBeUndefined();
  });
});

describe("parseReminder input guards", () => {
  it("reports an error for whitespace-only input", () => {
    const result = parse("   \t\n ");

    expect(result.reminder).toBeUndefined();
    expect(result.unparsedTokens).toEqual([]);
    expect(result.errors).toEqual(["Input is empty."]);
  });

  it("reports an error when no token can serve as a name", () => {
    const result = parse("daily");

    expect(result.reminder).toBeUndefined();
    expect(result.unparsedTokens).toEqual([]);
    expect(result.errors).toEqual([
      "Could not determine a name for the reminder.",
    ]);
  });
});

describe("parseReminder kind detection", () => {
  it("treats a short phrase with no drug signal as a task", () => {
    expect(expectReminder(parse("gym session")).kind).toBe("task");
  });

  it("treats a long phrase with no drug signal as a medication", () => {
    const reminder = expectReminder(parse("water the plants downstairs today"));

    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("water plants downstairs today");
  });

  it("does not re-append a leading task verb on a medication", () => {
    const reminder = expectReminder(parse("take atorvastatin 20mg daily"));

    expect(reminder.kind).toBe("medication");
    expect(reminder.name).toBe("atorvastatin");
  });

  it("drops a noise word that is not a task verb from a task name", () => {
    const reminder = expectReminder(parse("call the dentist"));

    expect(reminder.kind).toBe("task");
    expect(reminder.name).toBe("call dentist");
  });
});

describe("parseReminder priority words", () => {
  it("parses important as high", () => {
    const reminder = expectReminder(parse("important call mom"));

    expect(reminder.priority).toBe("high");
    expect(reminder.name).toBe("call mom");
  });

  it("parses low priority as low", () => {
    const reminder = expectReminder(parse("call plumber low priority"));

    expect(reminder.priority).toBe("low");
    expect(reminder.name).toBe("call plumber");
  });

  it("keeps a bare low in the name", () => {
    const reminder = expectReminder(parse("call plumber low bridge"));

    expect(reminder.priority).toBe("normal");
    expect(reminder.name).toBe("call plumber low bridge");
  });

  it("keeps a trailing low in the name", () => {
    expect(expectReminder(parse("call plumber low")).name).toBe(
      "call plumber low"
    );
  });
});

describe("parseReminder every-phrases", () => {
  it("parses every N days as an N*24 hour interval", () => {
    const reminder = expectReminder(parse("vitamin d 1000units every 3 days"));

    expect(reminder.schedule).toEqual(intervalTimes(72, NOW));
  });

  it("ignores every with an unknown unit", () => {
    const result = parse("aspirin 81mg every 8 bananas");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual(["8", "bananas"]);
  });

  it("ignores every with a count but no unit", () => {
    const result = parse("aspirin 81mg every 8");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual(["8"]);
  });

  it("ignores a count that overflows a 32-bit int", () => {
    const result = parse("aspirin 81mg every 99999999999 hours");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual(["99999999999", "hours"]);
  });

  it("ignores every followed by an unknown word", () => {
    const result = parse("aspirin 81mg every purple");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual(["purple"]);
  });

  it("ignores a trailing every", () => {
    const result = parse("atorvastatin 20mg every");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual([]);
  });
});

describe("parseReminder clock times", () => {
  it("parses a bare 12-hour time without at", () => {
    expect(expectReminder(parse("atorvastatin 20mg 8:30pm")).schedule).toEqual(
      dailyTimes(["20:30"])
    );
  });

  it("parses a 24-hour time", () => {
    expect(
      expectReminder(parse("atorvastatin 20mg at 18:30")).schedule
    ).toEqual(dailyTimes(["18:30"]));
  });

  it("truncates seconds", () => {
    expect(
      expectReminder(parse("atorvastatin 20mg at 8:00:00")).schedule
    ).toEqual(dailyTimes(["08:00"]));
  });

  it("maps 12am to midnight and 12pm to noon", () => {
    expect(expectReminder(parse("atorvastatin 20mg at 12am")).schedule).toEqual(
      dailyTimes(["00:00"])
    );
    expect(expectReminder(parse("atorvastatin 20mg at 12pm")).schedule).toEqual(
      dailyTimes(["12:00"])
    );
  });

  it("rejects an out-of-range hour", () => {
    const result = parse("atorvastatin 20mg at 25:00");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual(["25:00"]);
  });

  it("rejects out-of-range minutes", () => {
    expect(parse("atorvastatin 20mg at 8:70").unparsedTokens).toEqual(["8:70"]);
  });

  it("rejects a 12-hour clock hour outside 1-12", () => {
    expect(parse("atorvastatin 20mg at 13pm").unparsedTokens).toEqual(["13pm"]);
    expect(parse("atorvastatin 20mg at 0am").unparsedTokens).toEqual(["0am"]);
  });

  it("rejects a bare number as a time", () => {
    expect(parse("atorvastatin 20mg at 8").unparsedTokens).toEqual(["8"]);
  });

  it("ignores a trailing at", () => {
    const result = parse("atorvastatin 20mg at");

    expect(expectReminder(result).schedule).toEqual(dailyTimes(["09:00"]));
    expect(result.unparsedTokens).toEqual([]);
  });

  it("parses a bare named time", () => {
    expect(expectReminder(parse("atorvastatin 20mg bedtime")).schedule).toEqual(
      dailyTimes(["21:00"])
    );
  });

  it("merges and sorts times into an existing daily schedule", () => {
    expect(
      expectReminder(parse("atorvastatin 20mg daily at 9pm")).schedule
    ).toEqual(dailyTimes(["09:00", "21:00"]));
  });

  it("de-duplicates a repeated time", () => {
    expect(
      expectReminder(parse("atorvastatin 20mg every morning at 8am")).schedule
    ).toEqual(dailyTimes(["08:00"]));
  });

  it("replaces an interval schedule with a daily one (C# parity)", () => {
    expect(
      expectReminder(parse("aspirin 81mg every 8 hours at 9am")).schedule
    ).toEqual(dailyTimes(["09:00"]));
  });
});

describe("parseReminder weekly schedules", () => {
  it("starts a weekly schedule from a bare day name", () => {
    expect(expectReminder(parse("call mom monday")).schedule).toEqual(
      weeklyTimes([1], [])
    );
  });

  it("de-duplicates and sorts day names", () => {
    expect(
      expectReminder(parse("call mom friday monday monday")).schedule
    ).toEqual(weeklyTimes([1, 5], []));
  });

  it("accepts abbreviated day names", () => {
    expect(expectReminder(parse("call mom tues thurs")).schedule).toEqual(
      weeklyTimes([2, 4], [])
    );
  });

  it("leaves an existing weekly schedule alone on weekly", () => {
    expect(expectReminder(parse("call mom monday weekly")).schedule).toEqual(
      weeklyTimes([1], [])
    );
  });
});

describe("parseReminder inventory phrases", () => {
  it("parses a bare pill count", () => {
    const reminder = expectReminder(parse("atorvastatin 20mg 30 pills"));

    expect(reminder.pillsRemaining).toBe(30);
    expect(reminder.pillsPerDose).toBeUndefined();
    expect(reminder.refillReminderDays).toBeUndefined();
  });

  it("parses a standalone per dose phrase", () => {
    const result = parse("atorvastatin 20mg per dose");

    expect(result.unparsedTokens).toEqual([]);
    expect(expectReminder(result).pillsPerDose).toBeUndefined();
  });

  it("parses a fractional dose but leaves the count unparsed (C# parity)", () => {
    const result = parse("atorvastatin 20mg 1.5 per dose");

    expect(expectReminder(result).pillsPerDose).toBe(1.5);
    expect(result.unparsedTokens).toEqual(["1.5"]);
  });

  it("takes neither reading from N pills per dose (C# parity)", () => {
    const result = parse("atorvastatin 20mg 30 pills per dose");
    const reminder = expectReminder(result);

    expect(reminder.pillsRemaining).toBeUndefined();
    expect(reminder.pillsPerDose).toBeUndefined();
    expect(result.unparsedTokens).toEqual(["30", "pills"]);
  });

  it("ignores a number followed by an unrelated word", () => {
    expect(parse("atorvastatin 20mg 30 bananas").unparsedTokens).toEqual([
      "30",
      "bananas",
    ]);
  });

  it("parses refill in N without the days suffix", () => {
    const result = parse("atorvastatin 20mg refill in 7");

    expect(expectReminder(result).refillReminderDays).toBe(7);
    expect(result.unparsedTokens).toEqual([]);
  });

  it("ignores refill in with a non-numeric count", () => {
    const result = parse("atorvastatin 20mg refill in soon");

    expect(expectReminder(result).refillReminderDays).toBeUndefined();
    expect(result.unparsedTokens).toEqual(["refill", "soon"]);
  });

  it("ignores a trailing refill", () => {
    const result = parse("atorvastatin 20mg refill");

    expect(expectReminder(result).refillReminderDays).toBeUndefined();
    expect(result.unparsedTokens).toEqual(["refill"]);
  });

  it("ignores a trailing count", () => {
    expect(parse("atorvastatin 20mg 30").unparsedTokens).toEqual(["30"]);
  });
});

describe("parseReminder instruction phrases", () => {
  it.each([
    ["with water", "with water"],
    ["without food", "without food"],
    ["after meals", "after meals"],
    ["before meals", "before meals"],
  ])("parses %s", (phrase, expected) => {
    expect(
      expectReminder(parse(`atorvastatin 20mg ${phrase}`)).instructions
    ).toBe(expected);
  });

  it("ignores an unrecognized phrase after with", () => {
    const result = parse("atorvastatin 20mg with juice");

    expect(expectReminder(result).instructions).toBeUndefined();
    expect(result.unparsedTokens).toEqual(["juice"]);
  });

  it("ignores a trailing with", () => {
    const result = parse("atorvastatin 20mg with");

    expect(expectReminder(result).instructions).toBeUndefined();
    expect(result.unparsedTokens).toEqual([]);
  });
});
