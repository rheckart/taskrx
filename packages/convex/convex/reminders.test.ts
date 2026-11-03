/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * Upsert semantics for `reminders.create` and `reminders.update`.
 *
 * These exist for one defect: the device pushes a whole reminder row, and
 * `priority`, `tags` and `caregiverIds` have no author on the device — the
 * columns hold migration 004's defaults, so
 * sending them could only ever transmit a default over whatever a client that
 * *can* author them had set. `archive/expo-rn:apps/mobile/scripts/verify-reminder-migrations.mjs`
 * Part J pins the client half statically, because reproducing the failure needs
 * a second writer and one device cannot be two. This is the server half, and it
 * runs: a second writer is just a second call here.
 *
 * The `active`/`name`/`schedule` cases are the other side of the same fence.
 * Deleting the three fields from the payload outright would fix the clobber and
 * break the mirror, so the tests that assert those still flow are as load-bearing
 * as the ones that assert the other three do not.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const NOW = Date.UTC(2026, 6, 29, 12, 0, 0);

/** What the sync worker sends: no server-owned fields. */
// Only the discriminants are narrowed: a blanket `as const` would freeze
// `times` as `readonly string[]`, which the generated argument types reject.
const DEVICE_PUSH = {
  localId: "local-metformin",
  userId: OWNER,
  kind: "medication" as const,
  name: "Metformin",
  schedule: { type: "daily" as const, times: ["08:00"] },
  active: true,
  createdAt: NOW,
  updatedAt: NOW,
};

function deployment() {
  return convexTest(schema, modules).withIdentity({ subject: OWNER });
}

describe("reminders.create on a first insert", () => {
  it("fills the defaults, because a new document has nothing to preserve", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      priority: "normal",
      tags: [],
      caregiverIds: [],
      active: true,
    });
  });

  it("still honours the three when a caller does supply them", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      priority: "critical",
      tags: ["morning"],
      caregiverIds: ["care_1"],
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      priority: "critical",
      tags: ["morning"],
      caregiverIds: ["care_1"],
    });
  });
});

describe("reminders.create replayed for the same localId", () => {
  it("leaves priority, tags and caregiverIds set by another client alone", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    // A second writer — a web client, another device — sets the fields this
    // device cannot author.
    await t.mutation(api.reminders.update, {
      id,
      priority: "critical",
      tags: ["morning", "with-food"],
      caregiverIds: ["care_1"],
    });

    // A row left `pending` is re-enqueued by `reconcilePending`, so the same
    // create is pushed again on a later drain. This is the call that used to
    // reset all three to `normal`/`[]`/`[]`.
    await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      name: "Metformin 500mg",
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      name: "Metformin 500mg",
      priority: "critical",
      tags: ["morning", "with-food"],
      caregiverIds: ["care_1"],
    });
  });

  it("still applies the fields the medication is the source of truth for", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    // Discontinuing a medication just after adding it offline enqueues a second
    // create carrying `active: false`; returning early would strand the server.
    await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      name: "Metformin XR",
      schedule: { type: "daily", times: ["08:00", "20:00"] },
      active: false,
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      name: "Metformin XR",
      schedule: { type: "daily", times: ["08:00", "20:00"] },
      active: false,
    });
  });

  it("cannot resurrect a row deleted on this side", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);
    await t.mutation(api.reminders.softDelete, { id });

    await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      deletedAt: expect.any(Number) as number,
    });
    expect(await t.query(api.reminders.list, {})).toEqual([]);
  });
});

/**
 * The schedule validator is a closed union, widened with the shared domain one
 * (issue #12). It is the boundary that decides whether a malformed schedule is
 * rejected or stored, so each variant is exercised through a real write rather
 * than asserted against the validator's shape.
 */
describe("reminders.create and the schedule variants", () => {
  it("stores a reminder that comes due at a single moment", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      schedule: { type: "once", at: NOW + 86_400_000 },
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      schedule: { type: "once", at: NOW + 86_400_000 },
    });
  });

  it("stores a reminder with no schedule at all", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      schedule: { type: "none" },
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      schedule: { type: "none" },
    });
  });

  it("moves a backlog reminder onto the timeline by giving it a moment", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      schedule: { type: "none" },
    });

    await t.mutation(api.reminders.update, {
      id,
      schedule: { type: "once", at: NOW + 3_600_000 },
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      schedule: { type: "once", at: NOW + 3_600_000 },
    });
  });

  it("rejects a schedule variant that is not in the union", async () => {
    const t = deployment();

    await expect(
      t.mutation(api.reminders.create, {
        ...DEVICE_PUSH,
        // The point of spelling the union out: a malformed schedule fails at the
        // boundary instead of round-tripping and only breaking on the device.
        schedule: { type: "monthly", dayOfMonth: 1 } as unknown as {
          type: "none";
        },
      })
    ).rejects.toThrow();
  });

  it("rejects a single-moment schedule with no moment", async () => {
    const t = deployment();

    await expect(
      t.mutation(api.reminders.create, {
        ...DEVICE_PUSH,
        schedule: { type: "once" } as unknown as { type: "none" },
      })
    ).rejects.toThrow();
  });
});

describe("reminders.update", () => {
  it("leaves an omitted priority, tags and caregiverIds alone", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);
    await t.mutation(api.reminders.update, {
      id,
      priority: "high",
      tags: ["evening"],
      caregiverIds: ["care_2"],
    });

    // An unrelated edit: the medication's name changed on the device.
    await t.mutation(api.reminders.update, {
      id,
      name: "Metformin 850mg",
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      name: "Metformin 850mg",
      priority: "high",
      tags: ["evening"],
      caregiverIds: ["care_2"],
    });
  });
});

/**
 * Pause windows. A medication can be paused for a span (before surgery) and
 * resumed later; `until` absent means open-ended. `pauses` is optional at the
 * top level, so every document written before it existed stays valid.
 *
 * An empty array is a real value — it is how a client clears its pauses — so
 * the conditional spreads test `!== undefined`, and several cases below exist
 * only to tell `[]` apart from "not sent".
 */
const SURGERY = { from: NOW + 86_400_000, until: NOW + 7 * 86_400_000 };
const OPEN_ENDED = { from: NOW + 86_400_000 };

describe("reminders pauses on create", () => {
  it("stores the windows it is given", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [SURGERY],
    });

    expect((await t.query(api.reminders.get, { id }))?.pauses).toEqual([
      SURGERY,
    ]);
  });

  it("leaves the field undefined when none are sent", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    expect((await t.query(api.reminders.get, { id }))?.pauses).toBeUndefined();
  });

  it("accepts an open-ended window with no until", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [OPEN_ENDED],
    });

    const stored = (await t.query(api.reminders.get, { id }))?.pauses;
    expect(stored).toEqual([OPEN_ENDED]);
    expect(stored?.[0]).not.toHaveProperty("until");
  });

  it("rejects a window whose until is not a number", async () => {
    const t = deployment();

    await expect(
      t.mutation(api.reminders.create, {
        ...DEVICE_PUSH,
        pauses: [
          { from: NOW, until: "tomorrow" } as unknown as { from: number },
        ],
      })
    ).rejects.toThrow();
  });

  it("rejects a window with no from", async () => {
    const t = deployment();

    await expect(
      t.mutation(api.reminders.create, {
        ...DEVICE_PUSH,
        pauses: [{ until: NOW } as unknown as { from: number }],
      })
    ).rejects.toThrow();
  });
});

describe("reminders pauses on a create replayed for the same localId", () => {
  it("applies the windows it carries", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [SURGERY],
      updatedAt: NOW + 60_000,
    });

    expect((await t.query(api.reminders.get, { id }))?.pauses).toEqual([
      SURGERY,
    ]);
  });

  it("leaves existing windows alone when it carries none", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [SURGERY],
    });

    await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      name: "Metformin 500mg",
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      name: "Metformin 500mg",
      pauses: [SURGERY],
    });
  });
});

describe("reminders pauses on update", () => {
  it("sets them", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, DEVICE_PUSH);

    await t.mutation(api.reminders.update, { id, pauses: [SURGERY] });

    expect((await t.query(api.reminders.get, { id }))?.pauses).toEqual([
      SURGERY,
    ]);
  });

  it("stores an empty array, which is how a client clears them", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [SURGERY],
    });

    await t.mutation(api.reminders.update, { id, pauses: [] });

    expect((await t.query(api.reminders.get, { id }))?.pauses).toEqual([]);
  });

  it("leaves existing windows untouched when the update omits them", async () => {
    const t = deployment();
    const id = await t.mutation(api.reminders.create, {
      ...DEVICE_PUSH,
      pauses: [SURGERY, OPEN_ENDED],
    });

    await t.mutation(api.reminders.update, {
      id,
      name: "Metformin 850mg",
      updatedAt: NOW + 60_000,
    });

    expect(await t.query(api.reminders.get, { id })).toMatchObject({
      name: "Metformin 850mg",
      pauses: [SURGERY, OPEN_ENDED],
    });
  });
});
