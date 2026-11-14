import { v } from "convex/values";

import { internal } from "./_generated/api.js";
import {
  internalAction,
  internalMutation,
  internalQuery,
} from "./_generated/server.js";

/**
 * The mailbox road: one Fastmail mailbox, polled on the server every five minutes.
 *
 * Nothing on the device speaks JMAP. The poll reads `JMAP_TOKEN` from the environment; when
 * it is absent the action is a no-op, so a deployment without a mailbox costs nothing and
 * stores nothing. One mailbox per deployment (D15), which is why the cursor is a single row
 * and the token is an environment variable rather than a database field.
 *
 * The server does **no date parsing**. It keeps at most two sentence-ish spans that contain
 * a month name or a digit and stores those; the Kotlin reader in `:core` is the only
 * implementation of the date rule, so the two cannot drift. The body itself is discarded
 * after the spans are chosen (D14).
 *
 * A message is only kept when its sender is a watched source. That list syncs precisely
 * because this poll runs with no phone to ask.
 */

const JMAP_SESSION_URL = "https://api.fastmail.com/jmap/session";
const CORE_CAPABILITY = "urn:ietf:params:jmap:core";
const MAIL_CAPABILITY = "urn:ietf:params:jmap:mail";

/** One poll's page. A backlog is drained over several runs rather than in one transaction. */
const MAX_EMAILS = 50;

/** At most this many date-ish sentences are kept: more is a body, not a notice. */
const MAX_SENTENCES = 2;

/**
 * How far back the first poll looks when no cursor exists. A fresh deployment should not
 * vacuum a whole mailbox into notices; a week is enough to catch what arrived while the
 * feature was being set up.
 */
const FIRST_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * The overlap between polls. JMAP's `after` is strict, and a message received in the same
 * millisecond as the cursor would otherwise be missed. Re-reading a few minutes is free:
 * `notices` de-duplicates on the email id.
 */
const OVERLAP_MS = 10 * 60 * 1000;

interface JmapSession {
  apiUrl: string;
  primaryAccounts: Record<string, string>;
}

interface JmapEmail {
  id: string;
  subject?: string;
  receivedAt?: string;
  from?: { email?: string }[];
  textBody?: { partId?: string }[];
  bodyValues?: Record<string, { value?: string }>;
  preview?: string;
}

/** The cursor, or null before the first successful poll. */
export const getState = internalQuery({
  args: {},
  returns: v.union(v.object({ cursor: v.number() }), v.null()),
  handler: async (ctx) => {
    const row = await ctx.db.query("mailboxState").first();
    return row === null ? null : { cursor: row.cursor };
  },
});

/** Moves the cursor forward. Called only after every message in the page was handled. */
export const advanceCursor = internalMutation({
  args: { cursor: v.number() },
  returns: v.null(),
  handler: async (ctx, args) => {
    const row = await ctx.db.query("mailboxState").first();
    if (row === null) {
      await ctx.db.insert("mailboxState", {
        cursor: args.cursor,
        updatedAt: Date.now(),
      });
    } else {
      await ctx.db.patch(row._id, {
        cursor: args.cursor,
        updatedAt: Date.now(),
      });
    }
    return null;
  },
});

/**
 * Every watched email sender, with the person it belongs to.
 *
 * The poll is deployment-wide but notices are per-user, so the owner travels with the
 * address. Two people watching the same sender each get their own notice.
 */
export const watchedSenders = internalQuery({
  args: {},
  returns: v.array(v.object({ userId: v.string(), value: v.string() })),
  handler: async (ctx) => {
    const sources = await ctx.db.query("watchSources").take(500);
    return sources
      .filter((source) => source.kind === "email")
      .map((source) => ({
        userId: source.userId,
        value: source.value.toLowerCase(),
      }));
  },
});

/**
 * Stores one notice for one person, or returns null when that email is already kept.
 *
 * The de-duplication is on `(userId, externalId)`, the same key the device's intake uses,
 * so a replayed poll cannot pile up copies. `localId` is derived from the email id and the
 * owner so two people watching one sender do not collide on the device's row id.
 */
export const storeNotice = internalMutation({
  args: {
    userId: v.string(),
    localId: v.string(),
    sourceValue: v.string(),
    title: v.string(),
    sentence: v.optional(v.string()),
    receivedAt: v.number(),
    externalId: v.string(),
  },
  returns: v.union(v.id("notices"), v.null()),
  handler: async (ctx, args) => {
    const existing = await ctx.db
      .query("notices")
      .withIndex("by_user_external_id", (q) =>
        q.eq("userId", args.userId).eq("externalId", args.externalId)
      )
      .first();
    if (existing !== null) return null;

    const now = Date.now();
    return await ctx.db.insert("notices", {
      userId: args.userId,
      localId: args.localId,
      sourceKind: "email",
      sourceValue: args.sourceValue,
      title: args.title,
      receivedAt: args.receivedAt,
      externalId: args.externalId,
      resolution: "none",
      createdAt: now,
      updatedAt: now,
      ...(args.sentence !== undefined ? { sentence: args.sentence } : {}),
    });
  },
});

/**
 * One poll. Registered on a five-minute cron in `crons.ts`.
 *
 * The order is the plan's: read the cursor, ask JMAP for what arrived after it, keep only
 * watched senders, choose the date-ish spans, write, then advance the cursor. A failure
 * anywhere throws before the cursor moves, so the next run retries the same page and the
 * de-duplication makes the retry safe.
 */
export const poll = internalAction({
  args: {},
  returns: v.object({
    status: v.union(v.literal("no_token"), v.literal("polled")),
    fetched: v.number(),
    stored: v.number(),
  }),
  handler: async (ctx) => {
    const token = process.env.JMAP_TOKEN;
    if (token === undefined || token === "") {
      return { status: "no_token" as const, fetched: 0, stored: 0 };
    }

    const startedAt = Date.now();
    const state = await ctx.runQuery(internal.mailbox.getState, {});
    const cursor = state?.cursor ?? startedAt - FIRST_LOOKBACK_MS;
    const after = new Date(cursor - OVERLAP_MS).toISOString();

    const session = await readSession(token);
    const accountId = session.primaryAccounts[MAIL_CAPABILITY];
    if (accountId === undefined) {
      throw new Error("This JMAP account has no mail capability");
    }

    const emails = await queryEmails(token, session, accountId, after);
    const senders = await ctx.runQuery(internal.mailbox.watchedSenders, {});

    let stored = 0;
    for (const email of emails) {
      const from = (email.from ?? [])
        .map((address) => address.email?.toLowerCase())
        .filter((address): address is string => address !== undefined);
      const matches = senders.filter((sender) => from.includes(sender.value));
      if (matches.length === 0) continue;

      const sentence = keptSentences(bodyText(email));
      const receivedAt = Date.parse(email.receivedAt ?? "");
      const subject = email.subject?.trim() ?? "";
      for (const sender of matches) {
        const id = await ctx.runMutation(internal.mailbox.storeNotice, {
          userId: sender.userId,
          localId: `mailbox-${email.id}-${sender.userId}`,
          sourceValue: sender.value,
          title: subject === "" ? "(no subject)" : subject,
          ...(sentence === "" ? {} : { sentence }),
          receivedAt: Number.isNaN(receivedAt) ? startedAt : receivedAt,
          externalId: email.id,
        });
        if (id !== null) stored += 1;
      }
    }

    await ctx.runMutation(internal.mailbox.advanceCursor, {
      cursor: startedAt,
    });
    return { status: "polled" as const, fetched: emails.length, stored };
  },
});

async function readSession(token: string): Promise<JmapSession> {
  const response = await fetch(JMAP_SESSION_URL, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    throw new Error(`JMAP session request failed: ${String(response.status)}`);
  }
  return parseSession(await response.json());
}

async function queryEmails(
  token: string,
  session: JmapSession,
  accountId: string,
  after: string
): Promise<JmapEmail[]> {
  const response = await fetch(session.apiUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      using: [CORE_CAPABILITY, MAIL_CAPABILITY],
      methodCalls: [
        [
          "Email/query",
          {
            accountId,
            filter: { after },
            sort: [{ property: "receivedAt", isAscending: true }],
            limit: MAX_EMAILS,
          },
          "q",
        ],
        [
          "Email/get",
          {
            accountId,
            "#ids": {
              resultOf: "q",
              name: "Email/query",
              path: "/ids/*",
            },
            properties: [
              "id",
              "subject",
              "receivedAt",
              "from",
              "textBody",
              "bodyValues",
              "preview",
            ],
            bodyProperties: ["partId"],
            fetchTextBodyValues: true,
          },
          "g",
        ],
      ],
    }),
  });
  if (!response.ok) {
    throw new Error(`JMAP request failed: ${String(response.status)}`);
  }
  return parseEmails(await response.json());
}

function parseSession(json: unknown): JmapSession {
  if (typeof json !== "object" || json === null) {
    throw new Error("JMAP session was not an object");
  }
  const record = json as Record<string, unknown>;
  const apiUrl = record.apiUrl;
  const primaryAccounts = record.primaryAccounts;
  if (typeof apiUrl !== "string") {
    throw new Error("JMAP session has no apiUrl");
  }
  if (typeof primaryAccounts !== "object" || primaryAccounts === null) {
    throw new Error("JMAP session has no primaryAccounts");
  }
  return {
    apiUrl,
    primaryAccounts: primaryAccounts as Record<string, string>,
  };
}

function parseEmails(json: unknown): JmapEmail[] {
  if (typeof json !== "object" || json === null) return [];
  const responses = (json as Record<string, unknown>).methodResponses;
  if (!Array.isArray(responses)) return [];
  const entries: unknown[] = responses;
  for (const response of entries) {
    if (!Array.isArray(response) || response[0] !== "Email/get") continue;
    const args: unknown = response[1];
    if (typeof args !== "object" || args === null) continue;
    const list = (args as Record<string, unknown>).list;
    if (!Array.isArray(list)) continue;
    return list.filter(isEmail);
  }
  return [];
}

function isEmail(value: unknown): value is JmapEmail {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as Record<string, unknown>).id === "string"
  );
}

/** The plain-text body, falling back to JMAP's preview when a message has only HTML. */
function bodyText(email: JmapEmail): string {
  const values = email.bodyValues ?? {};
  const text = (email.textBody ?? [])
    .map((part) => (part.partId ? (values[part.partId]?.value ?? "") : ""))
    .join("\n")
    .trim();
  return text === "" ? (email.preview ?? "").trim() : text;
}

const MONTH =
  /\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)\b/i;

/**
 * Splits a body into sentences the way the Kotlin reader does: on `.`, `!` and `?`, and on
 * a blank line, keeping the terminator with its sentence.
 */
export function splitSentences(body: string): string[] {
  const sentences: string[] = [];
  let current = "";
  for (const line of body.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === "") {
      if (current.trim() !== "") sentences.push(current.trim());
      current = "";
      continue;
    }
    if (current !== "") current += " ";
    let index = 0;
    while (index < trimmed.length) {
      const character = trimmed.charAt(index);
      current += character;
      index += 1;
      if (character === "." || character === "!" || character === "?") {
        while (index < trimmed.length && /[.!?]/.test(trimmed.charAt(index))) {
          current += trimmed.charAt(index);
          index += 1;
        }
        sentences.push(current.trim());
        current = "";
      }
    }
  }
  if (current.trim() !== "") sentences.push(current.trim());
  return sentences.filter((sentence) => sentence !== "");
}

/**
 * The spans a notice keeps: at most two sentences that name a month or contain a digit.
 *
 * This is deliberately not a date parser. It only decides which text is worth keeping; the
 * device's reader decides what date, if any, that text names.
 */
export function keptSentences(body: string): string {
  return splitSentences(body)
    .filter((sentence) => MONTH.test(sentence) || /\d/.test(sentence))
    .slice(0, MAX_SENTENCES)
    .join(" ");
}
