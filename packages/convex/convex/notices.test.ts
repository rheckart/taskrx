/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * Notices as data: ownership, the replay upsert, resolution, and removal.
 *
 * The date reader and the proposal rules are `:core`'s and are tested there; what is pinned
 * here is that a notice is stored with the narrow retention the plan chose, that a second
 * device cannot read or resolve someone else's, and that a replayed create does not reset a
 * resolution the person already made.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 10, 13, 9, 0, 0);

const NOTICE = {
  localId: "notice-1",
  userId: OWNER,
  sourceKind: "email" as const,
  sourceValue: "waste@city.example",
  title: "Collection moved",
  sentence: "Collection moves to Wednesday 18 November.",
  receivedAt: NOW,
  externalId: "email-42",
  resolution: "none" as const,
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

describe("notices.create", () => {
  it("stores the narrow retention and reads it back", async () => {
    const t = as(deployment(), OWNER);
    const id = await t.mutation(api.notices.create, NOTICE);

    expect(
      await t.query(api.notices.getByLocalId, { localId: "notice-1" })
    ).toMatchObject({
      _id: id,
      sourceKind: "email",
      sourceValue: "waste@city.example",
      title: "Collection moved",
      sentence: "Collection moves to Wednesday 18 November.",
      externalId: "email-42",
      resolution: "none",
    });
  });

  it("refuses a notice written for another user", async () => {
    const t = as(deployment(), OWNER);
    await expect(
      t.mutation(api.notices.create, { ...NOTICE, userId: OTHER })
    ).rejects.toThrow("Cannot create a notice for another user");
  });

  it("replays as an upsert rather than a duplicate", async () => {
    const t = as(deployment(), OWNER);
    const id = await t.mutation(api.notices.create, NOTICE);
    const again = await t.mutation(api.notices.create, {
      ...NOTICE,
      resolution: "applied",
      resolvedAt: NOW + 1,
      updatedAt: NOW + 2,
    });

    expect(again).toBe(id);
    expect(await t.query(api.notices.list, {})).toHaveLength(1);
    expect(
      await t.query(api.notices.getByLocalId, { localId: "notice-1" })
    ).toMatchObject({
      resolution: "applied",
      resolvedAt: NOW + 1,
    });
  });
});

describe("notices.list", () => {
  it("returns only the caller's notices, newest received first", async () => {
    const raw = deployment();
    const t = as(raw, OWNER);
    await t.mutation(api.notices.create, {
      ...NOTICE,
      localId: "older",
      receivedAt: NOW - 1000,
    });
    await t.mutation(api.notices.create, {
      ...NOTICE,
      localId: "newer",
      receivedAt: NOW,
    });

    const mine = await t.query(api.notices.list, {});
    expect(mine.map((n) => n.localId)).toEqual(["newer", "older"]);

    const other = as(raw, OTHER);
    expect(await other.query(api.notices.list, {})).toEqual([]);
  });

  it("bounds the read", async () => {
    const t = as(deployment(), OWNER);
    for (let i = 0; i < 3; i++) {
      await t.mutation(api.notices.create, {
        ...NOTICE,
        localId: `n${String(i)}`,
        receivedAt: NOW + i,
      });
    }
    expect(await t.query(api.notices.list, { limit: 2 })).toHaveLength(2);
  });
});

describe("notices.update and remove", () => {
  it("resolves a notice and refuses another user's", async () => {
    const raw = deployment();
    const t = as(raw, OWNER);
    const id = await t.mutation(api.notices.create, NOTICE);

    await t.mutation(api.notices.update, {
      id,
      resolution: "dismissed",
      resolvedAt: NOW + 5,
    });
    expect(
      await t.query(api.notices.getByLocalId, { localId: "notice-1" })
    ).toMatchObject({
      resolution: "dismissed",
      resolvedAt: NOW + 5,
    });

    const other = as(raw, OTHER);
    await expect(
      other.mutation(api.notices.update, { id, resolution: "applied" })
    ).rejects.toThrow("Notice not found");
  });

  it("removes a notice outright", async () => {
    const t = as(deployment(), OWNER);
    const id = await t.mutation(api.notices.create, NOTICE);

    await t.mutation(api.notices.remove, { id });

    expect(
      await t.query(api.notices.getByLocalId, { localId: "notice-1" })
    ).toBeNull();
  });
});
