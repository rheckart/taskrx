/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * `doseLogs` — the POC-generation child table, superseded by
 * `reminderInstances` but still written by the device until the local data
 * migration has run.
 *
 * The interesting surface is `create`, which does *two* ownership checks: one
 * on the payload's `userId` and one on the parent medication. Only the second
 * stops a signed-in user attaching a dose to a prescription that is not theirs,
 * and it is the check a reader is most likely to assume the first already
 * covers. It gets its own test.
 *
 * `create` is also an upsert for the same reason `reminders.create` is, and
 * this is the table where returning early was worst: a dose logged offline and
 * marked taken before the first create drained would have kept `pending` on the
 * server forever, so a second device would show an already-taken dose as due.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 6, 29, 12, 0, 0);
const HOUR = 3_600_000;

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

function deployment() {
  const t = convexTest(schema, modules);
  return {
    anonymous: t,
    owner: t.withIdentity({ subject: OWNER }),
    other: t.withIdentity({ subject: OTHER }),
  };
}

/** A medication owned by `subject`, plus the dose-log payload that points at it. */
async function seed(
  as: ReturnType<typeof deployment>["owner"],
  subject: string,
  suffix = ""
) {
  const medicationId = await as.mutation(api.medications.create, {
    ...MED,
    localId: `${MED.localId}${suffix}`,
    userId: subject,
  });
  return {
    medicationId,
    dose: {
      localId: `local-dose${suffix}`,
      userId: subject,
      medicationId,
      scheduledTime: NOW,
      status: "pending" as const,
      createdAt: NOW,
      updatedAt: NOW,
    },
  };
}

describe("doseLogs.create", () => {
  it("refuses a payload addressed to another user", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);

    await expect(
      owner.mutation(api.doseLogs.create, { ...dose, userId: OTHER })
    ).rejects.toThrow(/Cannot create dose log for another user/);
  });

  /**
   * The check the payload's own `userId` does not make. Here the caller signs
   * their own name to a dose hung off someone else's prescription — every field
   * is self-consistent, and only the parent lookup rejects it.
   */
  it("refuses to attach a dose to a medication the caller does not own", async () => {
    const { owner, other } = deployment();
    const { medicationId } = await seed(owner, OWNER);

    await expect(
      other.mutation(api.doseLogs.create, {
        localId: "local-dose-intruder",
        userId: OTHER,
        medicationId,
        scheduledTime: NOW,
        status: "taken",
        createdAt: NOW,
        updatedAt: NOW,
      })
    ).rejects.toThrow(/Medication not found/);
  });

  it("refuses to take over a localId another user already holds", async () => {
    const { owner, other } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, dose);

    const theirs = await seed(other, OTHER, "-other");

    await expect(
      other.mutation(api.doseLogs.create, {
        ...theirs.dose,
        localId: dose.localId,
      })
    ).rejects.toThrow(/localId already exists for another user/);
  });

  it("stamps userId from the identity and omits absent optionals", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, dose);

    const [row] = await owner.query(api.doseLogs.list, {});
    expect(row).toMatchObject({ userId: OWNER, status: "pending" });
    expect(row).not.toHaveProperty("takenAt");
    expect(row).not.toHaveProperty("notes");
  });
});

describe("doseLogs.create replayed for the same localId", () => {
  /**
   * The defect this table exists to guard: two creates, the second carrying the
   * status the patient actually recorded. Returning `existing._id` unwritten
   * stranded the server on `pending`.
   */
  it("applies a status that changed before the first create drained", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const first = await owner.mutation(api.doseLogs.create, dose);

    const second = await owner.mutation(api.doseLogs.create, {
      ...dose,
      status: "taken",
      takenAt: NOW + 5 * 60_000,
      updatedAt: NOW + 5 * 60_000,
    });

    expect(second).toEqual(first);
    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({
      status: "taken",
      takenAt: NOW + 5 * 60_000,
      updatedAt: NOW + 5 * 60_000,
    });
  });

  it("leaves an omitted takenAt and notes alone rather than clearing them", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, {
      ...dose,
      status: "taken",
      takenAt: NOW + 60_000,
      notes: "with breakfast",
    });

    await owner.mutation(api.doseLogs.create, {
      ...dose,
      status: "taken",
      updatedAt: NOW + 120_000,
    });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ takenAt: NOW + 60_000, notes: "with breakfast" });
  });

  it("never rewrites createdAt, which is identity rather than payload", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, dose);

    await owner.mutation(api.doseLogs.create, {
      ...dose,
      createdAt: NOW + 999_999,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ createdAt: NOW });
  });

  /**
   * `medicationId` is rewritten deliberately — the ownership check above has
   * already validated the incoming parent against the caller, so a re-pointed
   * dose is a legitimate correction rather than an escape.
   */
  it("re-points the dose when the payload names a different medication", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, dose);
    const second = await seed(owner, OWNER, "-2");

    await owner.mutation(api.doseLogs.create, {
      ...dose,
      medicationId: second.medicationId,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ medicationId: second.medicationId });
  });
});

describe("doseLogs.list", () => {
  it("returns only the caller's own rows", async () => {
    const { owner, other } = deployment();
    const mine = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, mine.dose);
    const theirs = await seed(other, OTHER, "-other");
    await other.mutation(api.doseLogs.create, theirs.dose);

    expect(await owner.query(api.doseLogs.list, {})).toMatchObject([
      { userId: OWNER },
    ]);
    expect(await other.query(api.doseLogs.list, {})).toMatchObject([
      { userId: OTHER },
    ]);
  });

  it("narrows by medication, status and time window", async () => {
    const { owner } = deployment();
    const a = await seed(owner, OWNER);
    const b = await seed(owner, OWNER, "-b");

    await owner.mutation(api.doseLogs.create, a.dose);
    await owner.mutation(api.doseLogs.create, {
      ...a.dose,
      localId: "local-dose-a2",
      scheduledTime: NOW + 2 * HOUR,
      status: "taken",
    });
    await owner.mutation(api.doseLogs.create, {
      ...b.dose,
      localId: "local-dose-b1",
      scheduledTime: NOW + 4 * HOUR,
    });

    expect(
      await owner.query(api.doseLogs.list, { medicationId: a.medicationId })
    ).toHaveLength(2);
    expect(
      await owner.query(api.doseLogs.list, { status: "taken" })
    ).toMatchObject([{ localId: "local-dose-a2" }]);
    expect(
      await owner.query(api.doseLogs.list, { since: NOW + HOUR })
    ).toHaveLength(2);
    expect(
      await owner.query(api.doseLogs.list, { until: NOW + HOUR })
    ).toHaveLength(1);
    expect(
      await owner.query(api.doseLogs.list, {
        since: NOW + HOUR,
        until: NOW + 3 * HOUR,
      })
    ).toMatchObject([{ localId: "local-dose-a2" }]);
  });
});

describe("doseLogs.listForMedication", () => {
  /**
   * This query indexes on `by_medication`, which is not user-scoped — the
   * `userId` filter hung off it is the only thing standing between a guessed
   * medication id and another patient's dose history.
   */
  it("returns nothing for a medication belonging to someone else", async () => {
    const { owner, other } = deployment();
    const mine = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, mine.dose);

    expect(
      await owner.query(api.doseLogs.listForMedication, {
        medicationId: mine.medicationId,
      })
    ).toHaveLength(1);
    expect(
      await other.query(api.doseLogs.listForMedication, {
        medicationId: mine.medicationId,
      })
    ).toEqual([]);
  });
});

describe("doseLogs.getByLocalId", () => {
  it("does not resolve another user's localId", async () => {
    const { owner, other } = deployment();
    const { dose } = await seed(owner, OWNER);
    await owner.mutation(api.doseLogs.create, dose);

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ userId: OWNER });
    expect(
      await other.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toBeNull();
  });
});

describe("doseLogs.markTaken", () => {
  it("refuses a dose belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    await expect(
      other.mutation(api.doseLogs.markTaken, { id })
    ).rejects.toThrow(/Dose log not found/);
    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ status: "pending" });
  });

  it("honours an explicit takenAt", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    await owner.mutation(api.doseLogs.markTaken, { id, takenAt: NOW + 60_000 });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ status: "taken", takenAt: NOW + 60_000 });
  });

  it("falls back to wall-clock time when takenAt is omitted", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    const before = Date.now();
    await owner.mutation(api.doseLogs.markTaken, { id });
    const after = Date.now();

    const row = await owner.query(api.doseLogs.getByLocalId, {
      localId: dose.localId,
    });
    expect(row?.status).toBe("taken");
    expect(row?.takenAt).toBeGreaterThanOrEqual(before);
    expect(row?.takenAt).toBeLessThanOrEqual(after);
  });
});

describe("doseLogs.markSkipped", () => {
  it("refuses a dose belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    await expect(
      other.mutation(api.doseLogs.markSkipped, { id })
    ).rejects.toThrow(/Dose log not found/);
  });

  it("records the reason when one is given", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    await owner.mutation(api.doseLogs.markSkipped, { id, notes: "nauseous" });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ status: "skipped", notes: "nauseous" });
  });

  /**
   * Pinned as-is, and it diverges from `reminderInstances.markSkipped`.
   *
   * That sibling spreads `notes` conditionally, so an omitted argument leaves a
   * stored note alone. This one assigns `notes: args.notes` unconditionally, and
   * a Convex patch treats an explicit `undefined` as a field deletion — so
   * re-skipping without repeating the reason erases it. The test asserts the
   * behaviour that ships rather than the behaviour the sibling implies; see the
   * note raised alongside this suite.
   */
  it("clears an existing note when re-skipped without one", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);
    await owner.mutation(api.doseLogs.markSkipped, { id, notes: "nauseous" });

    await owner.mutation(api.doseLogs.markSkipped, { id });

    const row = await owner.query(api.doseLogs.getByLocalId, {
      localId: dose.localId,
    });
    expect(row).toMatchObject({ status: "skipped" });
    expect(row).not.toHaveProperty("notes");
  });
});

describe("doseLogs.markMissed", () => {
  it("refuses a dose belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, dose);

    await expect(
      other.mutation(api.doseLogs.markMissed, { id })
    ).rejects.toThrow(/Dose log not found/);
  });

  it("sets the status without disturbing a recorded note", async () => {
    const { owner } = deployment();
    const { dose } = await seed(owner, OWNER);
    const id = await owner.mutation(api.doseLogs.create, {
      ...dose,
      notes: "left at home",
    });

    await owner.mutation(api.doseLogs.markMissed, { id });

    expect(
      await owner.query(api.doseLogs.getByLocalId, { localId: dose.localId })
    ).toMatchObject({ status: "missed", notes: "left at home" });
  });
});

describe("doseLogs without a signed-in identity", () => {
  it("rejects reads", async () => {
    const { anonymous } = deployment();

    await expect(anonymous.query(api.doseLogs.list, {})).rejects.toThrow(
      /Not authenticated/
    );
    await expect(
      anonymous.query(api.doseLogs.getByLocalId, { localId: "local-dose" })
    ).rejects.toThrow(/Not authenticated/);
  });
});
