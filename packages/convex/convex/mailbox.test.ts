/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { afterEach, describe, expect, it, vi } from "vitest";

import { api, internal } from "./_generated/api.js";
import { keptSentences, splitSentences, unwrapForward } from "./mailbox.js";
import schema from "./schema.js";

/**
 * The mailbox road: the sentence spans the server keeps, the per-user write, the cursor,
 * and one poll end to end against a stubbed JMAP endpoint.
 *
 * The date reader is `:core`'s and is tested there. What is pinned here is that the server
 * keeps only date-ish spans, that it never parses them, that an unwatched sender is
 * dropped, and that a replayed poll cannot store the same email twice.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 10, 13, 9, 0, 0);

function deployment() {
  return convexTest(schema, modules);
}

function as(t: ReturnType<typeof deployment>, subject: string) {
  return t.withIdentity({ subject });
}

async function watch(
  t: ReturnType<typeof deployment>,
  userId: string,
  value: string
) {
  return await as(t, userId).mutation(api.watchSources.create, {
    localId: `source-${userId}-${value}`,
    userId,
    kind: "email",
    value,
    label: value,
    createdAt: NOW,
    updatedAt: NOW,
  });
}

describe("sentence spans", () => {
  it("splits on terminators and blank lines, keeping the terminator", () => {
    expect(
      splitSentences("The bins move. Please put them out. Thanks.")
    ).toEqual(["The bins move.", "Please put them out.", "Thanks."]);
    expect(
      splitSentences("Bin day changed\n\nCollection moves to Wednesday.")
    ).toEqual(["Bin day changed", "Collection moves to Wednesday."]);
    expect(splitSentences("one long line with no punctuation")).toEqual([
      "one long line with no punctuation",
    ]);
  });

  it("keeps only spans that name a month or contain a digit, at most two", () => {
    const body =
      "Hello there. Collection moves to Wednesday 18 November. A reminder will follow on 20 November. Thanks.";
    expect(keptSentences(body)).toBe(
      "Collection moves to Wednesday 18 November. A reminder will follow on 20 November."
    );
  });

  it("keeps a month name with no digit", () => {
    expect(keptSentences("Your appointment is in November.")).toBe(
      "Your appointment is in November."
    );
  });

  it("keeps nothing when no span names a date", () => {
    expect(
      keptSentences("Thank you for your patience. There have been delays.")
    ).toBe("");
  });
});

const FORWARD = [
  "Please remind me about this.",
  "",
  "----- Original message -----",
  "From: Chesapeake Animal Clinic <Notifications@PetDesk.com>",
  "To: rob@example.com",
  "Subject: Why Consistent Check-Ups Matter",
  "Date: Monday, November 16, 2026 10:21 AM",
  "",
  " <https://links.petdesk.com/ls/click?upn=u001.OGYeGf-2BxW8.9z>",
  "*(301) 855-5166 <tel:{{click_phone}}>*",
  "",
  "Faith may be due for services in the next month and a half.",
  "",
  "*Dec 28, 2026*",
  "",
  " • Vaccine Canine Rabies 3yr",
].join("\n");

describe("forwarded messages", () => {
  it("removes the header block and names the original sender", () => {
    const forward = unwrapForward(FORWARD);

    expect(forward.originalSenders).toEqual(["notifications@petdesk.com"]);
    expect(forward.body).not.toContain("Original message");
    expect(forward.body).not.toContain("November 16");
    expect(forward.body).toContain("Please remind me about this.");
    expect(forward.body).toContain("*Dec 28, 2026*");
  });

  it("keeps the notice's date, not the forward's, links or phone numbers", () => {
    expect(keptSentences(unwrapForward(FORWARD).body)).toBe(
      "Faith may be due for services in the next month and a half. *Dec 28, 2026*"
    );
  });

  it("reads Gmail's and Apple Mail's markers", () => {
    const gmail = unwrapForward(
      "---------- Forwarded message ---------\nFrom: <waste@city.example>\nDate: Mon, 16 Nov 2026\n\nBins move to 18 November."
    );
    expect(gmail.originalSenders).toEqual(["waste@city.example"]);
    expect(keptSentences(gmail.body)).toBe("Bins move to 18 November.");
    expect(
      unwrapForward(
        "Begin forwarded message:\n\nFrom: waste@city.example\nDate: 16 November 2026\n\nBins move."
      ).originalSenders
    ).toEqual(["waste@city.example"]);
  });

  it("leaves a message with no forward alone", () => {
    expect(unwrapForward("Collection moves to 18 November.")).toEqual({
      body: "Collection moves to 18 November.",
      originalSenders: [],
    });
  });
});

describe("mailbox state", () => {
  it("creates the cursor on the first advance and moves it after", async () => {
    const t = deployment();
    expect(await t.query(internal.mailbox.getState, {})).toBeNull();

    await t.mutation(internal.mailbox.advanceCursor, { cursor: NOW });
    expect(await t.query(internal.mailbox.getState, {})).toEqual({
      cursor: NOW,
    });

    await t.mutation(internal.mailbox.advanceCursor, { cursor: NOW + 1 });
    expect(await t.query(internal.mailbox.getState, {})).toEqual({
      cursor: NOW + 1,
    });
  });

  it("lists only email senders, with their owner", async () => {
    const t = deployment();
    await watch(t, OWNER, "Waste@City.Example");
    await as(t, OWNER).mutation(api.watchSources.create, {
      localId: "app-source",
      userId: OWNER,
      kind: "app",
      value: "com.example.waste",
      label: "Waste app",
      createdAt: NOW,
      updatedAt: NOW,
    });

    expect(await t.query(internal.mailbox.watchedSenders, {})).toEqual([
      { userId: OWNER, value: "waste@city.example" },
    ]);
  });
});

describe("mailbox.storeNotice", () => {
  const NOTICE = {
    userId: OWNER,
    localId: "mailbox-email-1-user_owner",
    sourceValue: "waste@city.example",
    title: "Collection moved",
    sentence: "Collection moves to Wednesday 18 November.",
    receivedAt: NOW,
    externalId: "email-1",
  };

  it("stores the narrow retention", async () => {
    const t = deployment();
    const id = await t.mutation(internal.mailbox.storeNotice, NOTICE);

    expect(id).not.toBeNull();
    expect(await as(t, OWNER).query(api.notices.list, {})).toMatchObject([
      {
        sourceKind: "email",
        sourceValue: "waste@city.example",
        title: "Collection moved",
        sentence: "Collection moves to Wednesday 18 November.",
        externalId: "email-1",
        resolution: "none",
      },
    ]);
  });

  it("returns null for an email already kept", async () => {
    const t = deployment();
    await t.mutation(internal.mailbox.storeNotice, NOTICE);

    expect(await t.mutation(internal.mailbox.storeNotice, NOTICE)).toBeNull();
    expect(await as(t, OWNER).query(api.notices.list, {})).toHaveLength(1);
  });

  it("keeps one copy per person when two watch the same sender", async () => {
    const t = deployment();
    await t.mutation(internal.mailbox.storeNotice, NOTICE);
    await t.mutation(internal.mailbox.storeNotice, {
      ...NOTICE,
      userId: OTHER,
      localId: "mailbox-email-1-user_other",
    });

    expect(await as(t, OWNER).query(api.notices.list, {})).toHaveLength(1);
    expect(await as(t, OTHER).query(api.notices.list, {})).toHaveLength(1);
  });
});

describe("mailbox.poll", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  function stubJmap(emails: unknown[]) {
    const fetchMock = vi.fn((input: string | URL) => {
      const url = String(input);
      if (url === "https://api.fastmail.com/jmap/session") {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              apiUrl: "https://api.fastmail.com/jmap/api/",
              primaryAccounts: {
                "urn:ietf:params:jmap:mail": "account-1",
              },
            }),
            { status: 200 }
          )
        );
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({
            methodResponses: [["Email/get", { list: emails }, "g"]],
          }),
          { status: 200 }
        )
      );
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  it("does nothing without a token", async () => {
    const t = deployment();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    expect(await t.action(internal.mailbox.poll, {})).toEqual({
      status: "no_token",
      fetched: 0,
      stored: 0,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("stores a watched sender's email and advances the cursor", async () => {
    vi.stubEnv("JMAP_TOKEN", "token");
    const t = deployment();
    await watch(t, OWNER, "waste@city.example");
    stubJmap([
      {
        id: "email-1",
        subject: "Collection moved",
        receivedAt: new Date(NOW).toISOString(),
        from: [{ email: "Waste@City.Example" }],
        textBody: [{ partId: "1" }],
        bodyValues: {
          "1": {
            value: "Hello. Collection moves to Wednesday 18 November. Thanks.",
          },
        },
      },
    ]);

    const result = await t.action(internal.mailbox.poll, {});

    expect(result).toEqual({ status: "polled", fetched: 1, stored: 1 });
    expect(await as(t, OWNER).query(api.notices.list, {})).toMatchObject([
      {
        sourceKind: "email",
        sourceValue: "waste@city.example",
        title: "Collection moved",
        sentence: "Collection moves to Wednesday 18 November.",
        externalId: "email-1",
      },
    ]);
    expect(await t.query(internal.mailbox.getState, {})).not.toBeNull();
  });

  it("drops an email from an unwatched sender but still advances", async () => {
    vi.stubEnv("JMAP_TOKEN", "token");
    const t = deployment();
    await watch(t, OWNER, "waste@city.example");
    stubJmap([
      {
        id: "email-2",
        subject: "Newsletter",
        receivedAt: new Date(NOW).toISOString(),
        from: [{ email: "news@example.com" }],
        textBody: [{ partId: "1" }],
        bodyValues: { "1": { value: "Sale on 20 November." } },
      },
    ]);

    expect(await t.action(internal.mailbox.poll, {})).toEqual({
      status: "polled",
      fetched: 1,
      stored: 0,
    });
    expect(await as(t, OWNER).query(api.notices.list, {})).toHaveLength(0);
    expect(await t.query(internal.mailbox.getState, {})).not.toBeNull();
  });

  it("does not store the same email twice across polls", async () => {
    vi.stubEnv("JMAP_TOKEN", "token");
    const t = deployment();
    await watch(t, OWNER, "waste@city.example");
    stubJmap([
      {
        id: "email-3",
        subject: "Collection moved",
        receivedAt: new Date(NOW).toISOString(),
        from: [{ email: "waste@city.example" }],
        textBody: [{ partId: "1" }],
        bodyValues: { "1": { value: "Collection moves to 18 November." } },
      },
    ]);

    await t.action(internal.mailbox.poll, {});
    const second = await t.action(internal.mailbox.poll, {});

    expect(second).toEqual({ status: "polled", fetched: 1, stored: 0 });
    expect(await as(t, OWNER).query(api.notices.list, {})).toHaveLength(1);
  });

  it("matches a forward on its original sender and keeps one notice per person", async () => {
    vi.stubEnv("JMAP_TOKEN", "token");
    const t = deployment();
    await watch(t, OWNER, "notifications@petdesk.com");
    await watch(t, OWNER, "rob@example.com");
    await watch(t, OTHER, "notifications@petdesk.com");
    stubJmap([
      {
        id: "email-5",
        subject: "Fwd: Why Consistent Check-Ups Matter",
        receivedAt: new Date(NOW).toISOString(),
        from: [{ email: "rob@example.com" }],
        textBody: [{ partId: "1" }],
        bodyValues: { "1": { value: FORWARD } },
      },
    ]);

    expect(await t.action(internal.mailbox.poll, {})).toEqual({
      status: "polled",
      fetched: 1,
      stored: 2,
    });
    expect(await as(t, OWNER).query(api.notices.list, {})).toMatchObject([
      {
        sourceValue: "notifications@petdesk.com",
        sentence:
          "Faith may be due for services in the next month and a half. *Dec 28, 2026*",
      },
    ]);
    expect(await as(t, OTHER).query(api.notices.list, {})).toHaveLength(1);
  });

  it("falls back to the preview when a message has no text body", async () => {
    vi.stubEnv("JMAP_TOKEN", "token");
    const t = deployment();
    await watch(t, OWNER, "waste@city.example");
    stubJmap([
      {
        id: "email-4",
        subject: "Collection moved",
        receivedAt: new Date(NOW).toISOString(),
        from: [{ email: "waste@city.example" }],
        preview: "Collection moves to 18 November.",
      },
    ]);

    await t.action(internal.mailbox.poll, {});

    expect(await as(t, OWNER).query(api.notices.list, {})).toMatchObject([
      { sentence: "Collection moves to 18 November." },
    ]);
  });
});
