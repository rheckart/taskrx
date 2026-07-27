import { describe, expect, it } from "vitest";

import {
  shouldApplyRemoteDoseLog,
  shouldApplyRemoteMedication,
} from "./conflict";

describe("shouldApplyRemoteMedication", () => {
  it("returns true when there is no local row", () => {
    expect(shouldApplyRemoteMedication(null, { updatedAt: 100 })).toBe(true);
  });

  it("returns true when the remote is strictly newer", () => {
    expect(
      shouldApplyRemoteMedication({ updatedAt: 100 }, { updatedAt: 200 })
    ).toBe(true);
  });

  it("returns false when the remote is strictly older", () => {
    expect(
      shouldApplyRemoteMedication({ updatedAt: 200 }, { updatedAt: 100 })
    ).toBe(false);
  });

  it("returns false when timestamps are equal (local wins ties)", () => {
    expect(
      shouldApplyRemoteMedication({ updatedAt: 100 }, { updatedAt: 100 })
    ).toBe(false);
  });
});

describe("shouldApplyRemoteDoseLog", () => {
  it("returns true when there is no local row", () => {
    expect(
      shouldApplyRemoteDoseLog(null, { updatedAt: 100, status: "pending" })
    ).toBe(true);
  });

  it("returns true when the remote is strictly newer and local is not taken", () => {
    expect(
      shouldApplyRemoteDoseLog(
        { updatedAt: 100, status: "pending" },
        { updatedAt: 200, status: "skipped" }
      )
    ).toBe(true);
  });

  it("returns false when the remote is strictly older", () => {
    expect(
      shouldApplyRemoteDoseLog(
        { updatedAt: 200, status: "pending" },
        { updatedAt: 100, status: "skipped" }
      )
    ).toBe(false);
  });

  it("returns false when timestamps are equal (local wins ties)", () => {
    expect(
      shouldApplyRemoteDoseLog(
        { updatedAt: 100, status: "pending" },
        { updatedAt: 100, status: "skipped" }
      )
    ).toBe(false);
  });

  it("returns false when local is taken, even if remote is newer", () => {
    expect(
      shouldApplyRemoteDoseLog(
        { updatedAt: 100, status: "taken" },
        { updatedAt: 200, status: "pending" }
      )
    ).toBe(false);
  });

  it("returns false when local is taken, even if remote is also taken and newer", () => {
    expect(
      shouldApplyRemoteDoseLog(
        { updatedAt: 100, status: "taken" },
        { updatedAt: 200, status: "taken" }
      )
    ).toBe(false);
  });
});
