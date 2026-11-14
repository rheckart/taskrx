/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * Watched sources: the gate on what is stored at all.
 *
 * The list syncs because the server-side mailbox poll filters by it, so ownership and the
 * replay upsert matter as much as they do for reminders. A share is never a watched source:
 * the person sharing something is the approval.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 10, 13, 9, 0, 0);

const SOURCE = {
  localId: "watch-1",
  userId: OWNER,
  kind: "email" as const,
  value: "waste@city.example",
  label: "City waste",
  createdAt: NOW,
  updatedAt: NOW,
};

function deployment() {
  return convexTest(schema, modules);
}

/** The same deployment, seen as one person. */
function as(t: ReturnType<typeof deployment>, subject: string) {
  return t.withIdentity({ subject });
}

describe("watchSources.create", () => {
  it("stores a sender and reads it back", async () => {
    const t = as(deployment(), OWNER);
    await t.mutation(api.watchSources.create, SOURCE);

    expect(
      await t.query(api.watchSources.getByLocalId, { localId: "watch-1" })
    ).toMatchObject({
      kind: "email",
      value: "waste@city.example",
      label: "City waste",
    });
  });

  it("refuses a share as a standing permission", async () => {
    const t = as(deployment(), OWNER);
    await expect(
      t.mutation(api.watchSources.create, { ...SOURCE, kind: "share" })
    ).rejects.toThrow("A share cannot be a watched source");
  });

  it("refuses a source written for another user", async () => {
    const t = as(deployment(), OWNER);
    await expect(
      t.mutation(api.watchSources.create, { ...SOURCE, userId: OTHER })
    ).rejects.toThrow("Cannot create a watched source for another user");
  });

  it("replays as an upsert rather than a duplicate", async () => {
    const t = as(deployment(), OWNER);
    const id = await t.mutation(api.watchSources.create, SOURCE);
    const again = await t.mutation(api.watchSources.create, {
      ...SOURCE,
      label: "Waste",
    });

    expect(again).toBe(id);
    expect(await t.query(api.watchSources.list, {})).toHaveLength(1);
    expect(
      await t.query(api.watchSources.getByLocalId, { localId: "watch-1" })
    ).toMatchObject({
      label: "Waste",
    });
  });
});

describe("watchSources.list", () => {
  it("returns only the caller's sources", async () => {
    const raw = deployment();
    const t = as(raw, OWNER);
    await t.mutation(api.watchSources.create, SOURCE);

    expect(await t.query(api.watchSources.list, {})).toHaveLength(1);
    const other = as(raw, OTHER);
    expect(await other.query(api.watchSources.list, {})).toEqual([]);
  });
});

describe("watchSources.update and remove", () => {
  it("renames a source and refuses another user's", async () => {
    const raw = deployment();
    const t = as(raw, OWNER);
    const id = await t.mutation(api.watchSources.create, SOURCE);

    await t.mutation(api.watchSources.update, {
      id,
      label: "Waste collection",
    });
    expect(
      await t.query(api.watchSources.getByLocalId, { localId: "watch-1" })
    ).toMatchObject({
      label: "Waste collection",
    });

    const other = as(raw, OTHER);
    await expect(
      other.mutation(api.watchSources.update, { id, label: "Mine" })
    ).rejects.toThrow("Watched source not found");
  });

  it("removes a source", async () => {
    const t = as(deployment(), OWNER);
    const id = await t.mutation(api.watchSources.create, SOURCE);

    await t.mutation(api.watchSources.remove, { id });

    expect(await t.query(api.watchSources.list, {})).toEqual([]);
  });
});
