import { describe, expect, it } from "vitest";

import { dailyTimes, intervalTimes, noneTimes } from "../domain/index";

import type { ReminderDraft } from "./draft";
import { isSavableDraft, readPhrase, withKind, withName } from "./draft";

/**
 * The rules the natural-language entry surface renders, stated as rules.
 *
 * The grammar itself is `reminder-parser.test.ts`'s subject, not this file's.
 * Where a case here does name a parsed value — `"20mg"`, `"with food"`,
 * `08:00` — the value is not what is being pinned: the claim is that the field
 * *survives the crossing* into the draft, and naming a real one is the only way
 * to state that. Deleting `dosage: reminder.dosage` has to fail a case here,
 * and it does. Those cases stay narrow for that reason, and a phrase is never
 * added here to cover a lexicon entry.
 *
 * What is asserted for its own sake is what the *review* step needs: that a
 * phrase always yields something editable, that a phrase the parser could not
 * read still yields something editable, that a correction cannot leave a task
 * carrying a dose or a drug identity, and what makes a draft savable.
 */

const NOW = Date.UTC(2026, 0, 15, 12, 0, 0);

function read(phrase: string): ReminderDraft {
  return readPhrase(phrase, { now: NOW }).draft;
}

describe("readPhrase", () => {
  it("reads a medication phrase without being told the Kind", () => {
    const reading = readPhrase("atorvastatin 20mg every morning with food", {
      now: NOW,
    });

    expect(reading.understood).toBe(true);
    expect(reading.reasons).toEqual([]);
    expect(reading.draft.kind).toBe("medication");
    expect(reading.draft.name).toBe("atorvastatin");
    expect(reading.draft.dosage).toBe("20mg");
    expect(reading.draft.instructions).toBe("with food");
    expect(reading.draft.schedule).toEqual(dailyTimes(["08:00"]));
  });

  it("reads a task phrase without being told the Kind", () => {
    const draft = read("call pharmacy friday 2pm");

    expect(draft.kind).toBe("task");
    expect(draft.name).toBe("call pharmacy");
    expect(draft.dosage).toBeUndefined();
  });

  it("carries the extracted supply details onto the draft", () => {
    const draft = read("metformin 500mg daily 30 pills 1 per dose refill in 7");

    expect(draft.pillsRemaining).toBe(30);
    expect(draft.pillsPerDose).toBe(1);
    expect(draft.refillReminderDays).toBe(7);
  });

  it("carries the extracted priority onto the draft", () => {
    expect(read("urgent call the surgery").priority).toBe("critical");
  });

  it("claims no RxNorm identity, because no phrase can name one", () => {
    expect(read("atorvastatin 20mg every morning").rxcui).toBeUndefined();
  });

  it("surfaces the tokens it could not place rather than dropping them", () => {
    const draft = read("aspirin 81mg daily wibble");

    expect(draft.unparsedTokens).toEqual(["wibble"]);
    expect(draft.name).toBe("aspirin");
  });

  it("reads the same phrase the same way every time", () => {
    const first = readPhrase("aspirin 81mg every 8 hours", { now: NOW });
    const second = readPhrase("aspirin 81mg every 8 hours", { now: NOW });

    expect(first).toEqual(second);
    expect(first.draft.schedule).toEqual(intervalTimes(8, NOW));
  });

  it("still offers a draft when it cannot read the phrase", () => {
    const reading = readPhrase("9am", { now: NOW });

    expect(reading.understood).toBe(false);
    expect(reading.reasons.length).toBeGreaterThan(0);
    expect(reading.draft.name).toBe("9am");
  });

  it("names no moment at all for a phrase it could not read", () => {
    expect(readPhrase("9am", { now: NOW }).draft.schedule).toEqual(noneTimes());
  });

  it("offers an empty draft for an empty phrase", () => {
    const reading = readPhrase("   ", { now: NOW });

    expect(reading.understood).toBe(false);
    expect(reading.draft.name).toBe("");
    expect(isSavableDraft(reading.draft)).toBe(false);
  });
});

describe("withKind", () => {
  it("drops the medication-only fields when a draft becomes a task", () => {
    const medication = read(
      "metformin 500mg daily 30 pills 1 per dose refill in 7"
    );
    const task = withKind(medication, "task");

    expect(task.kind).toBe("task");
    expect(task.dosage).toBeUndefined();
    expect(task.pillsRemaining).toBeUndefined();
    expect(task.pillsPerDose).toBeUndefined();
    expect(task.refillReminderDays).toBeUndefined();
  });

  it("keeps the fields both Kinds share when a draft becomes a task", () => {
    const medication = read("atorvastatin 20mg every morning with food");
    const task = withKind(medication, "task");

    expect(task.name).toBe("atorvastatin");
    expect(task.instructions).toBe("with food");
    expect(task.schedule).toEqual(dailyTimes(["08:00"]));
  });

  it("drops the drug identity when a draft becomes a task", () => {
    const picked: ReminderDraft = {
      ...read("metformin 500mg daily"),
      rxcui: "860975",
    };

    expect(withKind(picked, "task").rxcui).toBeUndefined();
  });

  it("keeps the drug identity while a draft is still a medication", () => {
    const picked: ReminderDraft = {
      ...read("metformin 500mg daily"),
      rxcui: "860975",
    };

    expect(withKind(picked, "medication").rxcui).toBe("860975");
  });

  it("leaves the rest of the draft alone when it becomes a medication", () => {
    const task = read("call pharmacy friday 2pm");
    const medication = withKind(task, "medication");

    expect(medication.kind).toBe("medication");
    expect(medication.name).toBe("call pharmacy");
    expect(medication.schedule).toEqual(task.schedule);
  });
});

describe("withName", () => {
  it("drops a drug identity the new name was never checked against", () => {
    const picked: ReminderDraft = {
      ...read("metformin 500mg daily"),
      rxcui: "860975",
    };

    expect(withName(picked, "Metformin").rxcui).toBeUndefined();
  });

  it("renames without disturbing the rest of the draft", () => {
    const draft = read("metformin 500mg daily");
    const renamed = withName(draft, "Metformin XR");

    expect(renamed.name).toBe("Metformin XR");
    expect(renamed.dosage).toBe("500mg");
    expect(renamed.schedule).toEqual(draft.schedule);
    expect(renamed.kind).toBe("medication");
  });
});

describe("isSavableDraft", () => {
  it("refuses a draft with no name", () => {
    const draft = read("aspirin 81mg daily");

    expect(isSavableDraft({ ...draft, name: "   " })).toBe(false);
  });

  it("refuses a draft whose schedule cannot produce a moment", () => {
    const draft = read("aspirin 81mg daily");

    expect(isSavableDraft({ ...draft, schedule: dailyTimes([]) })).toBe(false);
  });

  it("accepts a named draft with a usable schedule", () => {
    expect(isSavableDraft(read("aspirin 81mg daily"))).toBe(true);
  });

  it("accepts a named draft with no schedule at all, which is the Backlog", () => {
    const draft = read("aspirin 81mg daily");

    expect(isSavableDraft({ ...draft, schedule: noneTimes() })).toBe(true);
  });
});
