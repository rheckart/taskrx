/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * `medications` — the POC-generation table, still readable until the device-side
 * data migration has run (see `schema.ts`).
 *
 * Two properties carry the weight here. The first is per-user isolation: every
 * exported function calls `getCurrentUserId` and then has to actually scope its
 * reads and writes by it, and a check that is present but wired to the wrong
 * side of a comparison looks identical in review. Each function is therefore
 * exercised twice, once by the owner and once by a second signed-in user, and
 * the second call is the one that would catch it.
 *
 * The second is the upsert rule `create` shares with `reminders.create`: a
 * replayed create for the same `localId` must apply its payload rather than
 * return early, because the device picks `create` over `update` by whether its
 * local row has a `convex_id` yet, so an edit made while the first create is
 * still in the queue arrives as a *second* create. Returning `existing._id`
 * without writing left the server permanently behind — the client marks the row
 * synced and never re-sends it.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 6, 29, 12, 0, 0);

const MED = {
  localId: "local-metformin",
  userId: OWNER,
  name: "Metformin",
  scheduleType: "daily" as const,
  scheduleTimes: { times: ["08:00"] },
  active: true,
  createdAt: NOW,
  updatedAt: NOW,
};

/** Both accessors share one deployment, so `other` can see what `owner` wrote. */
function deployment() {
  const t = convexTest(schema, modules);
  return {
    anonymous: t,
    owner: t.withIdentity({ subject: OWNER }),
    other: t.withIdentity({ subject: OTHER }),
  };
}

describe("medications.list", () => {
  it("returns only the caller's own rows", async () => {
    const { owner, other } = deployment();
    await owner.mutation(api.medications.create, MED);
    await other.mutation(api.medications.create, {
      ...MED,
      localId: "local-lisinopril",
      userId: OTHER,
      name: "Lisinopril",
    });

    expect(await owner.query(api.medications.list, {})).toMatchObject([
      { name: "Metformin", userId: OWNER },
    ]);
    expect(await other.query(api.medications.list, {})).toMatchObject([
      { name: "Lisinopril", userId: OTHER },
    ]);
  });

  it("drops soft-deleted rows", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);
    expect(await owner.query(api.medications.list, {})).toHaveLength(1);

    await owner.mutation(api.medications.softDelete, { id });

    expect(await owner.query(api.medications.list, {})).toEqual([]);
  });
});

describe("medications.get", () => {
  it("returns null for a document belonging to someone else", async () => {
    const { owner, other } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    expect(await other.query(api.medications.get, { id })).toBeNull();
  });

  /**
   * Deliberately unlike `list`. The sync worker pulls a row by id to decide
   * whether to propagate a deletion, so hiding the tombstone here would make a
   * delete undetectable to the second device rather than merely invisible.
   */
  it("still returns a soft-deleted row to its owner", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);
    await owner.mutation(api.medications.softDelete, { id });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      deletedAt: expect.any(Number) as number,
      active: false,
    });
  });
});

describe("medications.getByLocalId", () => {
  it("does not resolve another user's localId", async () => {
    const { owner, other } = deployment();
    await owner.mutation(api.medications.create, MED);

    expect(
      await owner.query(api.medications.getByLocalId, {
        localId: MED.localId,
      })
    ).toMatchObject({ name: "Metformin" });
    expect(
      await other.query(api.medications.getByLocalId, {
        localId: MED.localId,
      })
    ).toBeNull();
  });
});

describe("medications.create", () => {
  it("refuses a payload addressed to another user", async () => {
    const { owner } = deployment();

    await expect(
      owner.mutation(api.medications.create, { ...MED, userId: OTHER })
    ).rejects.toThrow(/Cannot create medication for another user/);
  });

  it("refuses to take over a localId another user already holds", async () => {
    const { owner, other } = deployment();
    await owner.mutation(api.medications.create, MED);

    await expect(
      other.mutation(api.medications.create, { ...MED, userId: OTHER })
    ).rejects.toThrow(/localId already exists for another user/);
  });

  it("stamps userId from the identity, not from the payload", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      userId: OWNER,
      localId: MED.localId,
      name: "Metformin",
      active: true,
    });
  });

  it("omits rxcui and dosage entirely when the payload has neither", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);
    const doc = await owner.query(api.medications.get, { id });

    expect(doc).not.toHaveProperty("rxcui");
    expect(doc).not.toHaveProperty("dosage");
  });
});

describe("medications.create replayed for the same localId", () => {
  it("applies the newer payload instead of returning early", async () => {
    const { owner } = deployment();
    const first = await owner.mutation(api.medications.create, MED);

    // Discontinuing a medication just after adding it offline: the second
    // create is the only carrier of `active: false`.
    const second = await owner.mutation(api.medications.create, {
      ...MED,
      name: "Metformin XR",
      scheduleType: "interval",
      scheduleTimes: { hours: 12, startAt: NOW },
      active: false,
      updatedAt: NOW + 60_000,
    });

    expect(second).toEqual(first);
    expect(await owner.query(api.medications.get, { id: first })).toMatchObject(
      {
        name: "Metformin XR",
        scheduleType: "interval",
        scheduleTimes: { hours: 12, startAt: NOW },
        active: false,
        updatedAt: NOW + 60_000,
      }
    );
  });

  it("leaves an omitted rxcui and dosage alone rather than clearing them", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, {
      ...MED,
      rxcui: "860975",
      dosage: "500mg",
    });

    // The worker encodes a cleared field as an omission, so a replay that
    // simply does not mention them must not be read as "clear both".
    await owner.mutation(api.medications.create, {
      ...MED,
      updatedAt: NOW + 60_000,
    });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      rxcui: "860975",
      dosage: "500mg",
    });
  });

  it("never rewrites createdAt, which is identity rather than payload", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    await owner.mutation(api.medications.create, {
      ...MED,
      createdAt: NOW + 999_999,
      updatedAt: NOW + 60_000,
    });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      createdAt: NOW,
    });
  });

  /**
   * The counterpart of `reminders.create`'s "cannot resurrect a row deleted on
   * this side". `medications.create` reaches it differently — it never writes
   * `deletedAt`, so the tombstone survives the patch even though `active` is
   * rewritten — but the observable guarantee has to be the same, because `list`
   * is what the medication screen renders.
   */
  it("cannot bring a soft-deleted row back into list", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);
    await owner.mutation(api.medications.softDelete, { id });

    await owner.mutation(api.medications.create, {
      ...MED,
      active: true,
      updatedAt: NOW + 60_000,
    });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      deletedAt: expect.any(Number) as number,
    });
    expect(await owner.query(api.medications.list, {})).toEqual([]);
  });
});

describe("medications.update", () => {
  it("refuses a document belonging to someone else", async () => {
    const { owner, other } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    await expect(
      other.mutation(api.medications.update, { id, name: "Hijacked" })
    ).rejects.toThrow(/Medication not found/);
    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      name: "Metformin",
    });
  });

  it("leaves every omitted field alone", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, {
      ...MED,
      rxcui: "860975",
      dosage: "500mg",
    });

    await owner.mutation(api.medications.update, {
      id,
      name: "Metformin 850mg",
      updatedAt: NOW + 60_000,
    });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      name: "Metformin 850mg",
      rxcui: "860975",
      dosage: "500mg",
      scheduleType: "daily",
      active: true,
      updatedAt: NOW + 60_000,
    });
  });

  it("falls back to wall-clock time when the caller omits updatedAt", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    const before = Date.now();
    await owner.mutation(api.medications.update, { id, active: false });
    const after = Date.now();

    const doc = await owner.query(api.medications.get, { id });
    expect(doc?.active).toBe(false);
    expect(doc?.updatedAt).toBeGreaterThanOrEqual(before);
    expect(doc?.updatedAt).toBeLessThanOrEqual(after);
  });
});

describe("medications.softDelete", () => {
  it("refuses a document belonging to someone else", async () => {
    const { owner, other } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    await expect(
      other.mutation(api.medications.softDelete, { id })
    ).rejects.toThrow(/Medication not found/);
    expect(await owner.query(api.medications.list, {})).toHaveLength(1);
  });

  it("deactivates as well as tombstoning, so an active-only read agrees", async () => {
    const { owner } = deployment();
    const id = await owner.mutation(api.medications.create, MED);

    await owner.mutation(api.medications.softDelete, { id });

    expect(await owner.query(api.medications.get, { id })).toMatchObject({
      active: false,
      deletedAt: expect.any(Number) as number,
    });
  });
});

describe("medications without a signed-in identity", () => {
  it.each([
    [
      "list",
      (t: ReturnType<typeof deployment>["anonymous"]) =>
        t.query(api.medications.list, {}),
    ],
    [
      "getByLocalId",
      (t: ReturnType<typeof deployment>["anonymous"]) =>
        t.query(api.medications.getByLocalId, { localId: MED.localId }),
    ],
    [
      "create",
      (t: ReturnType<typeof deployment>["anonymous"]) =>
        t.mutation(api.medications.create, MED),
    ],
  ])("rejects %s", async (_name, call) => {
    const { anonymous } = deployment();

    await expect(call(anonymous)).rejects.toThrow(/Not authenticated/);
  });
});
