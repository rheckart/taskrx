/**
 * Ported from `ScheduleGrammarParserTests` in
 * `TaskRx.Core.Tests/ScheduleGrammarParserTests.cs` on the `archive/dotnet-maui`
 * branch.
 *
 * The first `describe` is the 17 xUnit cases, one-for-one and in source order.
 * The second covers the grammar the C# suite exercises only indirectly — the
 * two-character letter codes, the 24-hour and bare-hour time forms, and every
 * rejection path — because those branches are what a natural-language parser
 * gets wrong first.
 *
 * `TimeOnly` has no TypeScript counterpart, so times are asserted as the
 * `"HH:MM"` strings the domain uses everywhere else.
 */

import { describe, expect, it } from "vitest";

import type {
  ScheduleParseFailure,
  ScheduleParseResult,
  ScheduleParseSuccess,
} from "./schedule-grammar";
import { isGrammarToken, parseScheduleGrammar } from "./schedule-grammar";

function expectSuccess(result: ScheduleParseResult): ScheduleParseSuccess {
  if (result.type === "failure") {
    throw new Error(`Expected a successful parse, got: ${result.error}`);
  }
  return result;
}

function expectFailure(result: ScheduleParseResult): ScheduleParseFailure {
  if (result.type === "success") {
    throw new Error(
      `Expected a failed parse, got ${JSON.stringify(result.daysOfWeek)} / ${JSON.stringify(result.times)}`
    );
  }
  return result;
}

/** The times of a parse that is expected to succeed. */
function timesOf(input: string): readonly string[] {
  return expectSuccess(parseScheduleGrammar(input)).times;
}

describe("ScheduleGrammarParserTests (ported)", () => {
  it("Parse_Empty_ReturnsError", () => {
    const result = parseScheduleGrammar("");

    expect(result.type).toBe("failure");
    expect(expectFailure(result).error).not.toBe("");
  });

  it("Parse_DailyAtTime_ReturnsDailyNullDays", () => {
    const result = expectSuccess(parseScheduleGrammar("daily at 08:00"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual(["08:00"]);
  });

  it("Parse_EveryDayAtTime_ReturnsDailyNullDays", () => {
    const result = expectSuccess(parseScheduleGrammar("every day at 08:00"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual(["08:00"]);
  });

  it("Parse_Daily_NoTime_ReturnsSuccessEmptyTimes", () => {
    const result = expectSuccess(parseScheduleGrammar("daily"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual([]);
  });

  it("Parse_TwelveHour_AmPm", () => {
    expect(timesOf("daily at 8am")).toEqual(["08:00"]);
    expect(timesOf("daily at 8:30pm")).toEqual(["20:30"]);
    expect(timesOf("daily at 12am")).toEqual(["00:00"]);
    expect(timesOf("daily at 12pm")).toEqual(["12:00"]);
  });

  it("Parse_SpecificDays", () => {
    const result = expectSuccess(parseScheduleGrammar("mon wed fri at 08:00"));

    expect(result.daysOfWeek).toEqual([1, 3, 5]);
    expect(result.times).toHaveLength(1);
  });

  it("Parse_FullDayNames", () => {
    const result = expectSuccess(
      parseScheduleGrammar("Monday Wednesday at 09:00")
    );

    expect(result.daysOfWeek).toEqual([1, 3]);
  });

  it("Parse_LetterCombo_MWF", () => {
    const result = expectSuccess(parseScheduleGrammar("MWF at 09:00"));

    expect(result.daysOfWeek).toEqual([1, 3, 5]);
    expect(result.times).toHaveLength(1);
  });

  it("Parse_MultipleTimes", () => {
    const result = expectSuccess(parseScheduleGrammar("daily at 08:00, 20:00"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual(["08:00", "20:00"]);
  });

  it("Parse_Weekdays", () => {
    const result = expectSuccess(parseScheduleGrammar("weekdays at 09:00"));

    expect(result.daysOfWeek).toEqual([1, 2, 3, 4, 5]);
  });

  it("Parse_Weekends", () => {
    const result = expectSuccess(parseScheduleGrammar("weekends at 10:00"));

    expect(result.daysOfWeek).toEqual([0, 6]);
  });

  it("Parse_BareHour", () => {
    expect(timesOf("daily at 8")).toEqual(["08:00"]);
  });

  it("Parse_AndSeparator", () => {
    const result = expectSuccess(
      parseScheduleGrammar("daily at 08:00 and 20:00")
    );

    expect(result.times).toHaveLength(2);
  });

  it("Parse_UnknownTokens", () => {
    const result = expectFailure(
      parseScheduleGrammar("daily at 08:00 nonsense")
    );

    expect(result.error).toContain("nonsense");
    expect(result.unparseableTokens).toEqual(["nonsense"]);
  });

  it("IsGrammarToken_DayName_ReturnsTrue", () => {
    expect(isGrammarToken("Monday")).toBe(true);
    expect(isGrammarToken("monday")).toBe(true);
    expect(isGrammarToken("mon")).toBe(true);
  });

  it("IsGrammarToken_Keywords_ReturnsTrue", () => {
    expect(isGrammarToken("daily")).toBe(true);
    expect(isGrammarToken("every")).toBe(true);
    expect(isGrammarToken("at")).toBe(true);
    expect(isGrammarToken("weekdays")).toBe(true);
  });

  it("IsGrammarToken_NonGrammarToken_ReturnsFalse", () => {
    expect(isGrammarToken("atorvastatin")).toBe(false);
    expect(isGrammarToken("20mg")).toBe(false);
    expect(isGrammarToken("")).toBe(false);
  });
});

describe("parseScheduleGrammar: input handling", () => {
  it("rejects a missing input", () => {
    for (const input of [null, undefined, "   \t "]) {
      const result = expectFailure(parseScheduleGrammar(input));
      expect(result.error).toBe("No schedule text provided.");
      expect(result.unparseableTokens).toEqual([]);
    }
  });

  it("accepts tabs and commas as separators", () => {
    const result = expectSuccess(
      parseScheduleGrammar("mon,\twed\tat\t08:00,20:00")
    );

    expect(result.daysOfWeek).toEqual([1, 3]);
    expect(result.times).toEqual(["08:00", "20:00"]);
  });

  it("de-duplicates and sorts days and times", () => {
    const result = expectSuccess(
      parseScheduleGrammar("fri mon fri at 20:00, 8am, 08:00")
    );

    expect(result.daysOfWeek).toEqual([1, 5]);
    expect(result.times).toEqual(["08:00", "20:00"]);
  });

  it("reports every unparseable token, in input order", () => {
    const result = expectFailure(
      parseScheduleGrammar("daily at 08:00 with food")
    );

    expect(result.unparseableTokens).toEqual(["with", "food"]);
    expect(result.error).toContain("with, food");
  });

  it("fails when nothing at all was recognized", () => {
    const result = expectFailure(parseScheduleGrammar("at and"));

    expect(result.error).toBe(
      "Couldn't parse the schedule. Try 'daily at 08:00' or 'MWF at 09:00'."
    );
    expect(result.unparseableTokens).toEqual(["at", "and"]);
  });

  it("treats times without a day spec as daily", () => {
    const result = expectSuccess(parseScheduleGrammar("08:00"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual(["08:00"]);
  });

  it("accepts a day spec with no times", () => {
    const result = expectSuccess(parseScheduleGrammar("weekends"));

    expect(result.daysOfWeek).toEqual([0, 6]);
    expect(result.times).toEqual([]);
  });
});

describe("parseScheduleGrammar: day specs", () => {
  it("accepts every day-name spelling", () => {
    const cases: readonly (readonly [string, number])[] = [
      ["sun", 0],
      ["sunday", 0],
      ["mon", 1],
      ["monday", 1],
      ["tue", 2],
      ["tues", 2],
      ["tuesday", 2],
      ["wed", 3],
      ["wednesday", 3],
      ["thu", 4],
      ["thur", 4],
      ["thurs", 4],
      ["thursday", 4],
      ["fri", 5],
      ["friday", 5],
      ["sat", 6],
      ["saturday", 6],
    ];

    for (const [name, day] of cases) {
      expect(expectSuccess(parseScheduleGrammar(name)).daysOfWeek).toEqual([
        day,
      ]);
    }
  });

  it("prefers two-character codes over one-character ones", () => {
    expect(expectSuccess(parseScheduleGrammar("tuth")).daysOfWeek).toEqual([
      2, 4,
    ]);
    expect(expectSuccess(parseScheduleGrammar("sasu")).daysOfWeek).toEqual([
      0, 6,
    ]);
    expect(expectSuccess(parseScheduleGrammar("mtuwthf")).daysOfWeek).toEqual([
      1, 2, 3, 4, 5,
    ]);
  });

  /**
   * Both C# doc comments advertise `MTWTF`, but `OneCharCodes` holds only
   * `m`/`w`/`f` — a bare `t` is ambiguous between Tue and Thu, so the combo
   * dies on the second character. The behaviour is ported as-is; this pins it
   * so the gap between the docs and the code stays visible.
   */
  it("rejects MTWTF, which the C# documents but cannot parse", () => {
    const result = expectFailure(parseScheduleGrammar("mtwtf at 09:00"));

    expect(result.unparseableTokens).toEqual(["mtwtf"]);
  });

  it("rejects a combo it cannot consume to the end", () => {
    const result = expectFailure(parseScheduleGrammar("tux at 09:00"));

    expect(result.unparseableTokens).toEqual(["tux"]);
  });

  it("treats a bare 'day' as a day spec", () => {
    const result = expectSuccess(parseScheduleGrammar("day at 09:00"));

    expect(result.daysOfWeek).toBeNull();
    expect(result.times).toEqual(["09:00"]);
  });
});

describe("parseScheduleGrammar: times", () => {
  it("accepts dotted am/pm suffixes", () => {
    expect(timesOf("daily at 8a.m.")).toEqual(["08:00"]);
    expect(timesOf("daily at 9:15p.m.")).toEqual(["21:15"]);
  });

  it("accepts 24-hour times with one- or two-digit parts", () => {
    expect(timesOf("daily at 8:05")).toEqual(["08:05"]);
    expect(timesOf("daily at 8:5")).toEqual(["08:05"]);
    expect(timesOf("daily at 23:59")).toEqual(["23:59"]);
    expect(timesOf("daily at 00:00")).toEqual(["00:00"]);
  });

  it("accepts bare hours across the whole day", () => {
    expect(timesOf("daily at 0")).toEqual(["00:00"]);
    expect(timesOf("daily at 20")).toEqual(["20:00"]);
    expect(timesOf("daily at +8")).toEqual(["08:00"]);
  });

  /**
   * `isPm && hour < 12` only ever *adds* twelve, so a pm token whose hour is
   * already past noon is taken verbatim rather than rejected. Ported as-is.
   */
  it("passes through pm hours that are already 24-hour", () => {
    expect(timesOf("daily at 13pm")).toEqual(["13:00"]);
    expect(timesOf("daily at 23pm")).toEqual(["23:00"]);
  });

  it("rejects out-of-range times", () => {
    for (const token of ["25pm", "8:99am", "25:00", "08:75", "24", "-1"]) {
      const result = expectFailure(parseScheduleGrammar(`daily at ${token}`));
      expect(result.unparseableTokens).toEqual([token]);
    }
  });
});

describe("isGrammarToken", () => {
  it("accepts the remaining keywords", () => {
    expect(isGrammarToken("and")).toBe(true);
    expect(isGrammarToken("day")).toBe(true);
    expect(isGrammarToken("weekends")).toBe(true);
  });

  it("accepts letter combos and times", () => {
    expect(isGrammarToken("MWF")).toBe(true);
    expect(isGrammarToken("TuTh")).toBe(true);
    expect(isGrammarToken("08:00")).toBe(true);
    expect(isGrammarToken("8pm")).toBe(true);
    expect(isGrammarToken(" 8 ")).toBe(true);
  });

  it("rejects free text, dosages, and whitespace", () => {
    expect(isGrammarToken("lisinopril")).toBe(false);
    expect(isGrammarToken("10mg")).toBe(false);
    expect(isGrammarToken("   ")).toBe(false);
  });
});
