import { describe, expect, it } from "vitest";

import {
  shouldApplyRemoteReminder,
  shouldApplyRemoteReminderInstance,
} from "./conflict";

describe("shouldApplyRemoteReminder", () => {
  it("applies a remote reminder this device has never seen", () => {
    expect(shouldApplyRemoteReminder(null, { updatedAt: 100 })).toBe(true);
  });

  it("applies a remote edit made after the local one", () => {
    expect(
      shouldApplyRemoteReminder({ updatedAt: 100 }, { updatedAt: 200 })
    ).toBe(true);
  });

  it("keeps a local edit made after the remote one", () => {
    expect(
      shouldApplyRemoteReminder({ updatedAt: 200 }, { updatedAt: 100 })
    ).toBe(false);
  });

  it("keeps the local row when both were edited at the same instant", () => {
    expect(
      shouldApplyRemoteReminder({ updatedAt: 100 }, { updatedAt: 100 })
    ).toBe(false);
  });
});

describe("shouldApplyRemoteReminderInstance", () => {
  it("applies a remote instance this device has never seen", () => {
    expect(
      shouldApplyRemoteReminderInstance(null, {
        updatedAt: 100,
        status: "pending",
      })
    ).toBe(true);
  });

  it("applies a newer remote answer over an unanswered local one", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 100, status: "pending" },
        { updatedAt: 200, status: "skipped" }
      )
    ).toBe(true);
  });

  it("keeps a local answer made after the remote one", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 200, status: "pending" },
        { updatedAt: 100, status: "skipped" }
      )
    ).toBe(false);
  });

  it("keeps the local row when both were answered at the same instant", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 100, status: "pending" },
        { updatedAt: 100, status: "skipped" }
      )
    ).toBe(false);
  });

  /**
   * The safety-critical rule, and the reason this function is not just
   * `shouldApplyRemoteReminder` with an extra field. Story 44: "As someone
   * whose dose was recorded on one device, I want that record never
   * overwritten by stale data from another, so that a taken dose stays taken."
   *
   * Both directions are pinned because only the second one is load-bearing: a
   * rule that merely compared timestamps would pass the first case by accident
   * and fail the second.
   */
  it("never lets a remote pull erase an Instance this device recorded as taken", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 100, status: "taken" },
        { updatedAt: 200, status: "pending" }
      )
    ).toBe(false);
  });

  it("keeps the local taken record even when the remote is also taken and newer", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 100, status: "taken" },
        { updatedAt: 200, status: "taken" }
      )
    ).toBe(false);
  });

  /**
   * A snooze is not an answer, so it does not get the taken row's protection —
   * it is an ordinary last-write-wins field. Pinned because `snoozed` is the
   * one status the unified vocabulary has and `dose_logs` did not, so it is
   * the one place this table could silently diverge from its prior art.
   */
  it("lets a newer remote answer replace a local snooze", () => {
    expect(
      shouldApplyRemoteReminderInstance(
        { updatedAt: 100, status: "snoozed" },
        { updatedAt: 200, status: "taken" }
      )
    ).toBe(true);
  });
});
