#!/usr/bin/env node
/**
 * Data-survival check for the reminder migrations.
 *
 * `apps/mobile` has no test runner (the only vitest project in the repo is
 * `packages/shared`), so this is a standalone script rather than a spec file.
 * Run it with:
 *
 *   node apps/mobile/scripts/verify-reminder-migrations.mjs
 *
 * It exits non-zero on the first failed assertion so it can be wired into CI
 * as-is.
 *
 * These migrations run on devices holding real medication history, so the
 * check is about survival, not just syntax:
 *
 *   Part 0 — the SQL inlined in `src/db/index.ts` (what actually runs on the
 *            device) has not drifted from the `.sql` files (what gets
 *            reviewed).
 *   Part A — a fresh database applies 001 -> 008 and lands on the expected
 *            schema, including the widened `sync_queue.entity_type` CHECK.
 *            Stops at 008 because 009 then drops the legacy generation; Part K
 *            covers that.
 *   Part B — a database seeded with 3 medications, 5 dose logs, and 4 pending
 *            sync_queue rows under 001/002/003 keeps all 12 rows, with the
 *            right field mapping, after 004/005/006. Values are asserted, not
 *            counts. 006 rebuilds `sync_queue` (SQLite cannot ALTER a CHECK),
 *            so this is the part that proves the rebuild does not eat the
 *            queue.
 *   Part C — re-running is a no-op: nothing errors and nothing duplicates.
 *   Part D — after the rebuild the queue still behaves: the two new entity
 *            types insert, a bogus one is still rejected, and AUTOINCREMENT
 *            continues from the preserved high-water mark.
 *   Part K — 009 drops the legacy tables, deletes their queue entries, narrows
 *            the CHECK, and leaves every unified row untouched.
 *
 * Uses `node:sqlite`, so it needs Node 22+. This is NOT the same SQLite build
 * that ships in expo-sqlite; it proves the SQL is correct, not that a
 * particular device's SQLite has the same feature set. Migration 005 depends
 * on the JSON1 functions, which have been compiled in by default since SQLite
 * 3.38.
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const MIGRATIONS_DIR = join(HERE, "..", "src", "db", "migrations");
const DB_INDEX = join(HERE, "..", "src", "db", "index.ts");

// Sources the later parts read to extract SQL or assert code shape. Declared
// here rather than beside the medication-mirror checks that first needed them,
// because those are gone and these outlived them.
const REMINDERS_TS = join(HERE, "..", "src", "db", "repos", "reminders.ts");
const INSTANCES_TS = join(
  HERE,
  "..",
  "src",
  "db",
  "repos",
  "reminderInstances.ts"
);
const WORKER_TS = join(HERE, "..", "src", "sync", "worker.ts");
const CONVEX_REMINDERS_TS = join(
  HERE,
  "..",
  "..",
  "..",
  "packages",
  "convex",
  "convex",
  "reminders.ts"
);

const MIGRATION_FILES = [
  ["001_init", "001_init.sql"],
  ["002_add_convex_id", "002_add_convex_id.sql"],
  ["003_sync_queue_dead_letter", "003_sync_queue_dead_letter.sql"],
  ["004_unified_reminders", "004_unified_reminders.sql"],
  ["005_backfill_reminders", "005_backfill_reminders.sql"],
  ["006_widen_sync_queue", "006_widen_sync_queue.sql"],
  ["007_clear_projected_convex_ids", "007_clear_projected_convex_ids.sql"],
  ["008_sync_reconcile_indexes", "008_sync_reconcile_indexes.sql"],
  ["009_drop_legacy_generation", "009_drop_legacy_generation.sql"],
];

/**
 * Migrations up to and including 008 — the state in which the legacy
 * `medications` and `dose_logs` tables still exist.
 *
 * Parts A-D exercise the projection machinery that ran while both generations
 * were live. That history is still worth testing: an install upgrading from a
 * pre-cutover build replays exactly these steps, and 005's backfill is what
 * carries its data into the unified tables. But every one of those assertions
 * is about tables 009 then drops, so they run against this prefix. Part K
 * covers 009 itself.
 */
const THROUGH_008 = MIGRATION_FILES.slice(0, 8).map(([name]) => name);

const sqlByName = new Map(
  MIGRATION_FILES.map(([name, file]) => [
    name,
    readFileSync(join(MIGRATIONS_DIR, file), "utf8"),
  ])
);

function sql(name) {
  const text = sqlByName.get(name);
  if (text === undefined) throw new Error(`No SQL loaded for ${name}`);
  return text;
}

// ── source-reading helpers ────────────────────────────────────────────────
//
// Several parts assert on the SHAPE of TypeScript source rather than against a
// database: pulling a SQL template literal out of a repo, or a function body to
// check what it does and does not reference. These lived beside the mirror
// checks that first needed them; those are deleted and these are not.

/** Pulls a backtick template literal out of the TS source by its opening marker. */
function template(source, marker) {
  const start = source.indexOf(marker);
  if (start === -1) throw new Error(`marker not found: ${marker}`);
  const open = source.indexOf("`", start);
  const close = source.indexOf("`", open + 1);
  if (open === -1 || close === -1)
    throw new Error(`no template after ${marker}`);
  return source.slice(open + 1, close);
}

// The body of ONE function, brace-matched from its declaration.
//
// `template` above returns the first backtick literal after a marker, which for
// a SQL-carrying function is the query string -- it can never contain a call.
// An earlier version of the checks that used this fell back to searching the
// whole file, so deleting one function's enqueue still matched an identical
// call in its neighbour and the assertion passed against code that no longer
// existed. Scoping to a single body is what makes such checks discriminating.
//
// String contents are blanked before counting. Relying on "these bodies happen
// to contain no braces in literals" is exactly the kind of assumption that
// rots -- the deleted mirror carried `ELSE '{}'` inside its SQL -- and a single
// `"}"` in a body truncates the slice. That failure is silent for the
// negative `!/.../` checks -- they pass because the call moved outside the
// truncated window -- which is the same disease this extractor exists to cure.
//
// A regex-based blanker is not enough, and the gap was exploitable. An earlier
// version matched only `"`, `'` and `` ` ``, so a REGEX literal containing a
// brace -- `const stripBrace = /[}]/g;` -- still truncated the slice, and a real
// call added below it passed its `!/.../` check vacuously.
// That is the identical failure the string case was fixed for, one literal type
// over. This is a single pass over the text instead, blanking the contents of
// strings, template literals, regex literals AND comments, so callers no longer
// have to strip comments first either.
//
// Fixing that one literal spelling was not enough either. Deciding regex-vs-
// division from the previous character alone left `if (c) /[}]/.exec(x)` and
// `} \n /[}]/` unblanked, and the keyword list was anchored against text ending
// in the space before the `/`, so even `return /[}]/` never matched. Each was
// the same truncation with a different token in front of it. The decision is
// made against the last non-space character AND its index now, so:
//
//   * a keyword match tolerates the whitespace between it and the `/`;
//   * `)` is resolved by matching back to its `(` -- regex only when that paren
//     opened an `if`/`while`/`for`/`with` head, division otherwise;
//   * `}` closes a block, and `} / 2` is not a thing, so it is regex position;
//   * `]` stays division: `arr[0] /re/` does not parse, so it is not a shape a
//     mutation can hide behind.
//
// Length is preserved exactly (newlines included) so every offset computed on
// the blanked copy still indexes the original.
const REGEX_CAN_FOLLOW = /[([{,;:=!&|?+\-*%^~<>]$/;
const REGEX_CAN_FOLLOW_WORD =
  /\b(return|typeof|case|in|of|do|else|yield|await|new|delete|void|instanceof)\s*$/;
const CONTROL_HEAD = /\b(if|while|for|with)\s*$/;

function blankLiterals(text) {
  const out = text.split("");
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== "\n") out[k] = " ";
    }
  };
  // The last non-space character seen in code position, and where it sits.
  // Together these decide whether a `/` opens a regex or is a division operator.
  let before = "";
  let beforeIdx = -1;
  let i = 0;

  // Walks back over the already-blanked prefix to the `(` that `)` at `from`
  // closes. Literals before `from` are blanked by now, so parens inside them
  // cannot skew the depth count.
  const matchBackParen = (from) => {
    let depth = 0;
    for (let k = from; k >= 0; k -= 1) {
      if (out[k] === ")") depth += 1;
      else if (out[k] === "(") {
        depth -= 1;
        if (depth === 0) return k;
      }
    }
    return -1;
  };

  const stringEnd = (from, quote) => {
    let j = from + 1;
    while (j < text.length) {
      if (text[j] === "\\") {
        j += 2;
        continue;
      }
      if (text[j] === quote) return j;
      j += 1;
    }
    return text.length;
  };

  // The backtick that closes the template opening at `from`. Interpolations are
  // skipped by brace count, recursing through nested templates and strings,
  // because scanning for "the next backtick" closed the OUTER literal of
  // `` `a${ `in}ner` }b` `` on the INNER one -- leaving a `}` visible with no
  // `{` to match it. Same truncation as the regex case, one nesting level down.
  const templateEnd = (from) => {
    let j = from + 1;
    while (j < text.length) {
      const d = text[j];
      if (d === "\\") {
        j += 2;
        continue;
      }
      if (d === "`") return j;
      if (d === "$" && text[j + 1] === "{") {
        let depth = 1;
        j += 2;
        while (j < text.length && depth > 0) {
          const e = text[j];
          if (e === "\\") {
            j += 2;
            continue;
          }
          if (e === "`") {
            j = templateEnd(j) + 1;
            continue;
          }
          if (e === '"' || e === "'") {
            j = stringEnd(j, e) + 1;
            continue;
          }
          if (e === "{") depth += 1;
          else if (e === "}") depth -= 1;
          j += 1;
        }
        continue;
      }
      j += 1;
    }
    return text.length;
  };

  const atRegexPosition = () => {
    if (before === "") return true;
    if (REGEX_CAN_FOLLOW.test(before)) return true;
    if (REGEX_CAN_FOLLOW_WORD.test(text.slice(0, beforeIdx + 1))) return true;
    if (before === "}") return true;
    if (before === ")") {
      const open = matchBackParen(beforeIdx);
      return open !== -1 && CONTROL_HEAD.test(out.slice(0, open).join(""));
    }
    return false;
  };

  while (i < text.length) {
    const c = text[i];
    const next = text[i + 1];

    if (c === "/" && next === "/") {
      const nl = text.indexOf("\n", i);
      const end = nl === -1 ? text.length : nl;
      blank(i, end);
      i = end;
      continue;
    }
    if (c === "/" && next === "*") {
      const close = text.indexOf("*/", i + 2);
      const end = close === -1 ? text.length : close + 2;
      blank(i, end);
      i = end;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      const j = c === "`" ? templateEnd(i) : stringEnd(i, c);
      blank(i + 1, j);
      before = c;
      beforeIdx = i;
      i = Math.min(j + 1, text.length);
      continue;
    }
    if (c === "/" && atRegexPosition()) {
      let j = i + 1;
      let inClass = false;
      let closed = false;
      while (j < text.length) {
        const d = text[j];
        if (d === "\\") {
          j += 2;
          continue;
        }
        // An unescaped newline means this was never a regex -- fall through and
        // treat the `/` as division rather than eating the rest of the file.
        if (d === "\n") break;
        if (d === "[") inClass = true;
        else if (d === "]") inClass = false;
        else if (d === "/" && !inClass) {
          closed = true;
          break;
        }
        j += 1;
      }
      if (closed) {
        blank(i + 1, j);
        before = "/";
        beforeIdx = j;
        i = j + 1;
        continue;
      }
    }
    if (!/\s/.test(c)) {
      before = c;
      beforeIdx = i;
    }
    i += 1;
  }

  return out.join("");
}

function body(source, marker) {
  const start = source.indexOf(marker);
  if (start === -1) throw new Error(`marker not found: ${marker}`);
  const open = source.indexOf("{", start);
  if (open === -1) throw new Error(`no body after ${marker}`);
  // Count over a literal-blanked copy, but slice from the original so callers
  // still see real code. Offsets are preserved because blanking is same-length.
  const scan = blankLiterals(source);
  let depth = 0;
  for (let i = open; i < scan.length; i += 1) {
    if (scan[i] === "{") depth += 1;
    else if (scan[i] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, i);
    }
  }
  throw new Error(`unbalanced body for ${marker}`);
}

// ── assertions ────────────────────────────────────────────────────────────

let failures = 0;
let checks = 0;

function ok(label, condition, detail) {
  checks += 1;
  if (condition) {
    console.log(`  PASS  ${label}`);
  } else {
    failures += 1;
    console.log(
      `  FAIL  ${label}${detail === undefined ? "" : ` — ${detail}`}`
    );
  }
}

function eq(label, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  ok(label, a === e, `expected ${e}, got ${a}`);
}

function section(title) {
  console.log(`\n${title}`);
}

// ── migration runner (mirrors runMigrations in src/db/index.ts) ────────────

function open() {
  const db = new DatabaseSync(":memory:");
  db.exec(
    "CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL);"
  );
  return db;
}

/** Applies the named migrations, skipping any already recorded, each in a
 *  transaction — the same guard and framing `runMigrations` uses. */
function applyMigrations(db, names) {
  const applied = [];
  for (const name of names) {
    const already = db
      .prepare("SELECT name FROM _migrations WHERE name = ?")
      .get(name);
    if (already !== undefined) continue;

    db.exec("BEGIN");
    try {
      db.exec(sql(name));
      db.prepare(
        "INSERT INTO _migrations (name, applied_at) VALUES (?, ?)"
      ).run(name, Date.now());
      db.exec("COMMIT");
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
    applied.push(name);
  }
  return applied;
}

const ALL = MIGRATION_FILES.map(([name]) => name);

// ── Part 0: inlined SQL has not drifted from the .sql files ───────────────

function normalize(text) {
  return text
    .split("\n")
    .map((line) => line.replace(/--.*$/, ""))
    .join("\n")
    .replace(/\s+/g, " ")
    .trim();
}

function part0() {
  section("Part 0 — src/db/index.ts inline SQL matches the .sql files");
  const source = readFileSync(DB_INDEX, "utf8");
  const inline = new Map();
  const pattern = /name:\s*"([^"]+)",\s*sql:\s*`([\s\S]*?)`,/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    inline.set(match[1], match[2]);
  }

  eq("MIGRATIONS list order", [...inline.keys()], ALL);
  for (const name of ALL) {
    const inlined = inline.get(name);
    ok(
      `${name} inlined SQL == ${name}.sql`,
      inlined !== undefined && normalize(inlined) === normalize(sql(name))
    );
  }
}

// ── Part A: fresh database, 001 -> 005 ────────────────────────────────────

function columns(db, table) {
  return db
    .prepare(`PRAGMA table_info(${table})`)
    .all()
    .map((row) => row.name);
}

function indexes(db, table) {
  return db
    .prepare(`PRAGMA index_list(${table})`)
    .all()
    .map((row) => row.name)
    .filter((name) => name.startsWith("idx_"))
    .sort();
}

function partA() {
  section("Part A — fresh database, 001 -> 008 (both generations live)");
  const db = open();
  const applied = applyMigrations(db, THROUGH_008);
  eq("every migration through 008 applied", applied, THROUGH_008);

  const tables = db
    .prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
    )
    .all()
    .map((row) => row.name)
    .filter((name) => !name.startsWith("sqlite_"));
  eq("tables", tables, [
    "_migrations",
    "dose_logs",
    "medications",
    "reminder_instances",
    "reminders",
    "sync_queue",
  ]);

  eq("reminders columns", columns(db, "reminders"), [
    "id",
    "user_id",
    "kind",
    "name",
    "rxcui",
    "dosage",
    "instructions",
    "schedule",
    "priority",
    "tags",
    "pills_remaining",
    "pills_per_dose",
    "refill_reminder_days",
    "caregiver_ids",
    "active",
    "created_at",
    "updated_at",
    "sync_status",
    "convex_id",
  ]);

  eq("reminder_instances columns", columns(db, "reminder_instances"), [
    "id",
    "reminder_id",
    "user_id",
    "reminder_kind",
    "scheduled_time",
    "status",
    "taken_at",
    "snoozed_until",
    "snooze_count",
    "notes",
    "created_at",
    "updated_at",
    "sync_status",
    "convex_id",
  ]);

  eq("reminders indexes", indexes(db, "reminders"), [
    "idx_reminders_convex_id",
    "idx_reminders_sync_status",
    "idx_reminders_user_active",
    "idx_reminders_user_kind",
  ]);
  eq("reminder_instances indexes", indexes(db, "reminder_instances"), [
    "idx_reminder_instances_convex_id",
    "idx_reminder_instances_pending",
    "idx_reminder_instances_reminder",
    "idx_reminder_instances_sync_status",
    "idx_reminder_instances_user_scheduled",
  ]);

  const fk = db.prepare("PRAGMA foreign_key_list(reminder_instances)").all();
  ok(
    "FK reminder_instances.reminder_id -> reminders.id ON DELETE CASCADE",
    fk.length === 1 &&
      fk[0].table === "reminders" &&
      fk[0].from === "reminder_id" &&
      fk[0].to === "id" &&
      fk[0].on_delete === "CASCADE"
  );

  const pending = db
    .prepare(
      "SELECT sql FROM sqlite_master WHERE name = 'idx_reminder_instances_pending'"
    )
    .get();
  ok(
    "pending index is partial (WHERE status = 'pending')",
    typeof pending?.sql === "string" &&
      /WHERE status = 'pending'/.test(pending.sql)
  );

  // CHECK constraints actually bite.
  const rejects = (label, statement) => {
    let threw = false;
    try {
      db.exec(statement);
    } catch {
      threw = true;
    }
    ok(label, threw);
  };
  rejects(
    "reminders.kind CHECK rejects an unknown kind",
    `INSERT INTO reminders (id,user_id,kind,name,schedule,priority,tags,caregiver_ids,active,created_at,updated_at)
     VALUES ('x','u','chore','n','{}','normal','[]','[]',1,0,0)`
  );
  rejects(
    "reminder_instances.status CHECK rejects an unknown status",
    `INSERT INTO reminders (id,user_id,kind,name,schedule,priority,tags,caregiver_ids,active,created_at,updated_at)
       VALUES ('r1','u','task','n','{}','normal','[]','[]',1,0,0);
     INSERT INTO reminder_instances (id,reminder_id,user_id,reminder_kind,scheduled_time,status,snooze_count,created_at,updated_at)
       VALUES ('i1','r1','u','task',0,'deferred',0,0,0)`
  );
  const accepts = (label, statement) => {
    let error = null;
    try {
      db.exec(statement);
    } catch (e) {
      error = e;
    }
    ok(label, error === null, error === null ? undefined : String(error));
  };

  accepts(
    "reminder_instances accepts the new 'snoozed' status",
    `INSERT INTO reminder_instances (id,reminder_id,user_id,reminder_kind,scheduled_time,status,snoozed_until,snooze_count,created_at,updated_at)
     VALUES ('i2','r1','u','task',0,'snoozed',99,1,0,0)`
  );

  // ── 006: the rebuilt sync_queue ────────────────────────────────────────
  //
  // The rebuild has to reproduce the table as 001 + 003 left it, not just the
  // widened CHECK. A column dropped or reordered here would silently corrupt
  // every subsequent enqueue.
  eq("sync_queue columns", columns(db, "sync_queue"), [
    "id",
    "entity_type",
    "entity_id",
    "operation",
    "payload",
    "attempts",
    "last_error",
    "created_at",
    "dead_letter",
  ]);
  eq("sync_queue indexes", indexes(db, "sync_queue"), [
    "idx_sync_queue_created",
    "idx_sync_queue_dead_letter",
    "idx_sync_queue_entity",
  ]);

  const queueSql = db
    .prepare("SELECT sql FROM sqlite_master WHERE name = 'sync_queue'")
    .get();
  ok(
    "sync_queue is still AUTOINCREMENT",
    typeof queueSql?.sql === "string" && /AUTOINCREMENT/.test(queueSql.sql)
  );
  ok(
    "the scratch rebuild table did not survive",
    db
      .prepare("SELECT name FROM sqlite_master WHERE name = 'sync_queue_006'")
      .get() === undefined
  );

  const enqueue = (entityType, operation) =>
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
     VALUES ('${entityType}', 'e-${entityType}-${operation}', '${operation}', '{}', 0)`;

  accepts(
    "sync_queue still accepts entity_type 'medication'",
    enqueue("medication", "create")
  );
  accepts(
    "sync_queue still accepts entity_type 'dose_log'",
    enqueue("dose_log", "create")
  );
  accepts(
    "sync_queue accepts entity_type 'reminder'",
    enqueue("reminder", "create")
  );
  accepts(
    "sync_queue accepts entity_type 'reminder_instance'",
    enqueue("reminder_instance", "update")
  );
  // Widened, not dropped.
  rejects(
    "sync_queue.entity_type CHECK still rejects an unknown entity type",
    enqueue("appointment", "create")
  );
  rejects(
    "sync_queue.operation CHECK survived the rebuild",
    enqueue("reminder", "upsert")
  );
  rejects(
    "sync_queue.payload NOT NULL survived the rebuild",
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
     VALUES ('reminder', 'e-null', 'create', NULL, 0)`
  );

  const defaults = db
    .prepare(
      "SELECT attempts, last_error, dead_letter FROM sync_queue WHERE entity_id = 'e-reminder-create'"
    )
    .get();
  eq("sync_queue defaults survived the rebuild", defaults, {
    attempts: 0,
    last_error: null,
    dead_letter: 0,
  });

  db.close();
}

// ── Part B: seeded database survives 004/005 ──────────────────────────────

const MEDS = [
  {
    id: "med-aaaa-1111",
    user_id: "user_alpha",
    name: "Metformin",
    rxcui: "860975",
    dosage: "500 mg",
    schedule_type: "daily",
    schedule_times: JSON.stringify({ times: ["08:00", "20:00"] }),
    active: 1,
    created_at: 1_700_000_000_000,
    updated_at: 1_700_000_100_000,
    sync_status: "synced",
    convex_id: "cx_med_1",
  },
  {
    id: "med-bbbb-2222",
    user_id: "user_alpha",
    name: "Ibuprofen",
    rxcui: null,
    dosage: null,
    schedule_type: "interval",
    schedule_times: JSON.stringify({ hours: 8, startAt: 1_700_000_500_000 }),
    active: 1,
    created_at: 1_700_000_200_000,
    updated_at: 1_700_000_200_000,
    sync_status: "pending",
    convex_id: null,
  },
  {
    id: "med-cccc-3333",
    user_id: "user_beta",
    name: "Lisinopril",
    rxcui: "29046",
    dosage: "10 mg",
    schedule_type: "daily",
    schedule_times: JSON.stringify({ times: [] }),
    active: 0,
    created_at: 1_700_000_300_000,
    updated_at: 1_700_000_400_000,
    sync_status: "error",
    convex_id: "cx_med_3",
  },
];

const LOGS = [
  {
    id: "dose-1",
    user_id: "user_alpha",
    medication_id: "med-aaaa-1111",
    scheduled_time: 1_700_001_000_000,
    taken_at: 1_700_001_060_000,
    status: "taken",
    notes: "with breakfast",
    created_at: 1_700_001_000_000,
    updated_at: 1_700_001_060_000,
    sync_status: "synced",
    convex_id: "cx_dose_1",
  },
  {
    id: "dose-2",
    user_id: "user_alpha",
    medication_id: "med-aaaa-1111",
    scheduled_time: 1_700_002_000_000,
    taken_at: null,
    status: "missed",
    notes: null,
    created_at: 1_700_002_000_000,
    updated_at: 1_700_002_500_000,
    sync_status: "pending",
    convex_id: null,
  },
  {
    id: "dose-3",
    user_id: "user_alpha",
    medication_id: "med-bbbb-2222",
    scheduled_time: 1_700_003_000_000,
    taken_at: null,
    status: "skipped",
    notes: "felt fine",
    created_at: 1_700_003_000_000,
    updated_at: 1_700_003_100_000,
    sync_status: "error",
    convex_id: null,
  },
  {
    id: "dose-4",
    user_id: "user_alpha",
    medication_id: "med-bbbb-2222",
    scheduled_time: 1_700_004_000_000,
    taken_at: null,
    status: "pending",
    notes: null,
    created_at: 1_700_004_000_000,
    updated_at: 1_700_004_000_000,
    sync_status: "pending",
    convex_id: null,
  },
  {
    id: "dose-5",
    user_id: "user_beta",
    medication_id: "med-cccc-3333",
    scheduled_time: 1_700_005_000_000,
    taken_at: 1_700_005_030_000,
    status: "taken",
    notes: null,
    created_at: 1_700_005_000_000,
    updated_at: 1_700_005_030_000,
    sync_status: "synced",
    convex_id: "cx_dose_5",
  },
];

/**
 * Pending queue rows under the OLD narrow CHECK — only 'medication' and
 * 'dose_log' are insertable at seed time, which is the point: these are the
 * rows a device is holding when 006 rebuilds the table under them. The set
 * deliberately spans every column that carries state a worker depends on:
 * a retried entry with `attempts` and `last_error`, a dead-lettered entry,
 * all three operations, and an explicit id sequence.
 */
const QUEUE = [
  {
    id: 1,
    entity_type: "medication",
    entity_id: "med-aaaa-1111",
    operation: "create",
    payload: JSON.stringify({
      localId: "med-aaaa-1111",
      userId: "user_alpha",
      name: "Metformin",
    }),
    attempts: 0,
    last_error: null,
    created_at: 1_700_000_050_000,
    dead_letter: 0,
  },
  {
    id: 2,
    entity_type: "dose_log",
    entity_id: "dose-2",
    operation: "update",
    payload: JSON.stringify({ localId: "dose-2", status: "missed" }),
    attempts: 3,
    last_error: "Network request failed",
    created_at: 1_700_002_600_000,
    dead_letter: 0,
  },
  {
    id: 3,
    entity_type: "medication",
    entity_id: "med-cccc-3333",
    operation: "delete",
    payload: JSON.stringify({
      localId: "med-cccc-3333",
      userId: "user_beta",
      deletedAt: 1_700_000_400_000,
    }),
    attempts: 5,
    last_error: "Medication med-cccc-3333 not found on Convex",
    created_at: 1_700_000_450_000,
    dead_letter: 1,
  },
  {
    id: 4,
    entity_type: "dose_log",
    entity_id: "dose-3",
    operation: "create",
    payload: JSON.stringify({ localId: "dose-3", status: "pending" }),
    attempts: 0,
    last_error: null,
    created_at: 1_700_003_050_000,
    dead_letter: 0,
  },
];

/**
 * The AUTOINCREMENT watermark the seeded queue leaves behind. Row 5 is
 * inserted and then deleted, which is what a drained queue looks like: the
 * table is short of its high-water mark, so a rebuild that only copies rows
 * would hand out id 5 twice. 006 copies `sqlite_sequence` across for exactly
 * this reason.
 */
const QUEUE_SEQ = 5;

function seedQueue(db) {
  const row = db.prepare(
    `INSERT INTO sync_queue (id, entity_type, entity_id, operation, payload, attempts, last_error, created_at, dead_letter)
     VALUES (?,?,?,?,?,?,?,?,?)`
  );
  for (const q of QUEUE) {
    row.run(
      q.id,
      q.entity_type,
      q.entity_id,
      q.operation,
      q.payload,
      q.attempts,
      q.last_error,
      q.created_at,
      q.dead_letter
    );
  }
  row.run(
    QUEUE_SEQ,
    "dose_log",
    "dose-5",
    "update",
    "{}",
    0,
    null,
    1_700_005_050_000,
    0
  );
  db.exec(`DELETE FROM sync_queue WHERE id = ${String(QUEUE_SEQ)}`);
}

/** Every seeded queue row, asserted column by column rather than by count. */
function assertQueue(db, phase) {
  for (const q of QUEUE) {
    const r = db.prepare("SELECT * FROM sync_queue WHERE id = ?").get(q.id);
    if (r === undefined) {
      ok(`${phase}: sync_queue row ${String(q.id)} exists`, false);
      continue;
    }
    eq(`${phase}: sync_queue #${String(q.id)} (${q.entity_type}) intact`, r, q);
  }

  const count = db.prepare("SELECT count(*) AS n FROM sync_queue").get().n;
  eq(`${phase}: sync_queue row count`, count, QUEUE.length);

  const seq = db
    .prepare("SELECT seq FROM sqlite_sequence WHERE name = 'sync_queue'")
    .get();
  eq(`${phase}: AUTOINCREMENT watermark preserved`, seq, { seq: QUEUE_SEQ });

  const stray = db
    .prepare("SELECT count(*) AS n FROM sqlite_sequence WHERE name LIKE ?")
    .get("sync_queue_%").n;
  eq(`${phase}: no stray sqlite_sequence row for the scratch table`, stray, 0);
}

function seed(db) {
  const med = db.prepare(
    `INSERT INTO medications (id,user_id,name,rxcui,dosage,schedule_type,schedule_times,active,created_at,updated_at,sync_status,convex_id)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`
  );
  for (const m of MEDS) {
    med.run(
      m.id,
      m.user_id,
      m.name,
      m.rxcui,
      m.dosage,
      m.schedule_type,
      m.schedule_times,
      m.active,
      m.created_at,
      m.updated_at,
      m.sync_status,
      m.convex_id
    );
  }
  const log = db.prepare(
    `INSERT INTO dose_logs (id,user_id,medication_id,scheduled_time,taken_at,status,notes,created_at,updated_at,sync_status,convex_id)
     VALUES (?,?,?,?,?,?,?,?,?,?,?)`
  );
  for (const l of LOGS) {
    log.run(
      l.id,
      l.user_id,
      l.medication_id,
      l.scheduled_time,
      l.taken_at,
      l.status,
      l.notes,
      l.created_at,
      l.updated_at,
      l.sync_status,
      l.convex_id
    );
  }
}

const EXPECTED_SCHEDULE = {
  "med-aaaa-1111": '{"type":"daily","times":["08:00","20:00"]}',
  "med-bbbb-2222": '{"type":"interval","hours":8,"startAt":1700000500000}',
  "med-cccc-3333": '{"type":"daily","times":[]}',
};

function assertBackfill(db, phase) {
  for (const m of MEDS) {
    const r = db.prepare("SELECT * FROM reminders WHERE id = ?").get(m.id);
    if (r === undefined) {
      ok(`${phase}: reminder ${m.id} exists`, false);
      continue;
    }
    eq(
      `${phase}: ${m.name} -> reminders row`,
      {
        id: r.id,
        user_id: r.user_id,
        kind: r.kind,
        name: r.name,
        rxcui: r.rxcui,
        dosage: r.dosage,
        instructions: r.instructions,
        schedule: r.schedule,
        priority: r.priority,
        tags: r.tags,
        pills_remaining: r.pills_remaining,
        pills_per_dose: r.pills_per_dose,
        refill_reminder_days: r.refill_reminder_days,
        caregiver_ids: r.caregiver_ids,
        active: r.active,
        created_at: r.created_at,
        updated_at: r.updated_at,
        sync_status: r.sync_status,
        convex_id: r.convex_id,
      },
      {
        id: m.id,
        user_id: m.user_id,
        kind: "medication",
        name: m.name,
        rxcui: m.rxcui,
        dosage: m.dosage,
        instructions: null,
        schedule: EXPECTED_SCHEDULE[m.id],
        priority: "normal",
        tags: "[]",
        pills_remaining: null,
        pills_per_dose: null,
        refill_reminder_days: null,
        caregiver_ids: "[]",
        active: m.active,
        created_at: m.created_at,
        updated_at: m.updated_at,
        // NOT inherited from the medication. A projection is its own sync
        // entity: it starts unsynced, and `convex_id` stays NULL until
        // `api.reminders.create` returns a real `Id<"reminders">`. Copying
        // `m.convex_id` across put an `Id<"medications">` where the sync
        // worker expected an `Id<"reminders">`.
        sync_status: "pending",
        convex_id: null,
      }
    );
  }

  for (const l of LOGS) {
    const i = db
      .prepare("SELECT * FROM reminder_instances WHERE id = ?")
      .get(l.id);
    if (i === undefined) {
      ok(`${phase}: instance ${l.id} exists`, false);
      continue;
    }
    eq(
      `${phase}: ${l.id} -> reminder_instances row`,
      {
        id: i.id,
        reminder_id: i.reminder_id,
        user_id: i.user_id,
        reminder_kind: i.reminder_kind,
        scheduled_time: i.scheduled_time,
        status: i.status,
        taken_at: i.taken_at,
        snoozed_until: i.snoozed_until,
        snooze_count: i.snooze_count,
        notes: i.notes,
        created_at: i.created_at,
        updated_at: i.updated_at,
        sync_status: i.sync_status,
        convex_id: i.convex_id,
      },
      {
        id: l.id,
        reminder_id: l.medication_id,
        user_id: l.user_id,
        reminder_kind: "medication",
        scheduled_time: l.scheduled_time,
        status: l.status,
        taken_at: l.taken_at,
        snoozed_until: null,
        snooze_count: 0,
        notes: l.notes,
        created_at: l.created_at,
        updated_at: l.updated_at,
        // Same reasoning as the reminders half: `l.convex_id` is an
        // `Id<"doseLogs">`, not an `Id<"reminderInstances">`.
        sync_status: "pending",
        convex_id: null,
      }
    );
  }

  const count = (table) =>
    db.prepare(`SELECT count(*) AS n FROM ${table}`).get().n;
  eq(`${phase}: reminders row count`, count("reminders"), MEDS.length);
  eq(
    `${phase}: reminder_instances row count`,
    count("reminder_instances"),
    LOGS.length
  );

  // Widen-then-migrate: the source tables must be untouched.
  eq(`${phase}: medications still present`, count("medications"), MEDS.length);
  eq(`${phase}: dose_logs still present`, count("dose_logs"), LOGS.length);
  const original = db
    .prepare(
      "SELECT rxcui, schedule_type, schedule_times FROM medications WHERE id = ?"
    )
    .get("med-aaaa-1111");
  eq(
    `${phase}: medications row unmodified (005 copies, never moves)`,
    original,
    {
      rxcui: "860975",
      schedule_type: "daily",
      schedule_times: '{"times":["08:00","20:00"]}',
    }
  );
}

function partB() {
  section(
    "Part B — 3 medications + 5 dose logs + 4 queue rows survive 004/005/006"
  );
  const db = open();
  applyMigrations(db, [
    "001_init",
    "002_add_convex_id",
    "003_sync_queue_dead_letter",
  ]);
  seed(db);
  seedQueue(db);

  // Guard the guard: if the seed could already hold a unified entity type,
  // Part B would not be testing the widening at all.
  let seededWide = false;
  try {
    db.exec(
      `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
       VALUES ('reminder', 'pre-006', 'create', '{}', 0)`
    );
    seededWide = true;
  } catch {
    seededWide = false;
  }
  ok("before 006 the narrow CHECK rejects entity_type 'reminder'", !seededWide);

  const applied = applyMigrations(db, THROUGH_008);
  eq("only 004/005/006 applied to the seeded db", applied, [
    "004_unified_reminders",
    "005_backfill_reminders",
    "006_widen_sync_queue",
    "007_clear_projected_convex_ids",
    "008_sync_reconcile_indexes",
  ]);

  assertBackfill(db, "after first run");
  assertQueue(db, "after first run");
  return db;
}

// ── Part C: re-running is a no-op ─────────────────────────────────────────

function partC(db) {
  section("Part C — re-running is idempotent");

  // C1: the runner's _migrations guard makes a second pass a no-op.
  const reapplied = applyMigrations(db, THROUGH_008);
  eq("_migrations guard skips everything on a second pass", reapplied, []);

  // C2: force-execute 004, 005 and 006 again with the guard bypassed. This is
  // the partially-applied case the guard does not cover. 001-003 are excluded:
  // 002 and 003 are bare `ALTER TABLE ADD COLUMN` and have never been
  // re-runnable, which predates this work.
  //
  // 006 is the one that has to earn this. It is a table rebuild — drop and
  // recreate — so "no-op on a second run" is a property of how it is written
  // (`DROP TABLE IF EXISTS` on the scratch name, copy from whatever
  // `sync_queue` currently is), not something the statements get for free the
  // way 005's `INSERT OR IGNORE` does.
  let error = null;
  try {
    db.exec(sql("004_unified_reminders"));
    db.exec(sql("005_backfill_reminders"));
    db.exec(sql("006_widen_sync_queue"));
  } catch (e) {
    error = e;
  }
  ok(
    "004 + 005 + 006 re-execute with the guard bypassed without error",
    error === null,
    error === null ? undefined : String(error)
  );

  assertBackfill(db, "after forced re-run");
  assertQueue(db, "after forced re-run");
  return db;
}

// ── Part D: the rebuilt queue is usable ───────────────────────────────────

function partD(db) {
  section("Part D — the rebuilt sync_queue accepts the unified entity types");

  const insert = db.prepare(
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
     VALUES (?,?,?,?,?)`
  );
  const tryInsert = (entityType, operation) => {
    try {
      insert.run(entityType, `x-${entityType}`, operation, "{}", 0);
      return null;
    } catch (e) {
      return e;
    }
  };

  const reminderError = tryInsert("reminder", "create");
  ok(
    "enqueue entity_type 'reminder' succeeds",
    reminderError === null,
    reminderError === null ? undefined : String(reminderError)
  );

  // The AUTOINCREMENT watermark carried across, so the first id handed out
  // after the rebuild is one past it — not a reused id.
  const firstNewId = db
    .prepare("SELECT id FROM sync_queue WHERE entity_id = 'x-reminder'")
    .get();
  eq("first id after the rebuild continues the sequence", firstNewId, {
    id: QUEUE_SEQ + 1,
  });

  const instanceError = tryInsert("reminder_instance", "update");
  ok(
    "enqueue entity_type 'reminder_instance' succeeds",
    instanceError === null,
    instanceError === null ? undefined : String(instanceError)
  );

  ok(
    "a bogus entity type is still rejected (CHECK widened, not dropped)",
    tryInsert("appointment", "create") !== null
  );
  ok(
    "a bogus operation is still rejected",
    tryInsert("reminder", "upsert") !== null
  );

  // The pre-006 rows are still readable alongside the new ones — the queue is
  // one table, and the worker drains it in `created_at` order.
  const byType = db
    .prepare(
      "SELECT entity_type, count(*) AS n FROM sync_queue GROUP BY entity_type ORDER BY entity_type"
    )
    .all();
  eq("queue holds both generations after the rebuild", byType, [
    { entity_type: "dose_log", n: 2 },
    { entity_type: "medication", n: 2 },
    { entity_type: "reminder", n: 1 },
    { entity_type: "reminder_instance", n: 1 },
  ]);

  db.close();
}

// ── Part F: 007 repairs devices that already applied the old 005 ──────────

/**
 * 005 no longer copies `convex_id` across, so a fresh install never sees the
 * fault. A device that upgraded, though, already has an `Id<"medications">`
 * sitting in `reminders.convex_id`, and the medication mirror now enqueues
 * reminder sync entries — which is exactly what would hand that id to
 * `api.reminders.update`. 007 is what makes those devices safe, so it is
 * tested against the pre-fix on-disk state rather than a clean one.
 */
// ── Part G: the reconcile queries actually select the right rows ──────────
//
// `reconcilePending` is what drains migration 005's backfill: without it the
// backfilled rows sit `pending` forever and the unified tables never reach
// Convex, which is the whole defect M7 names. Both copies are checked because
// the reminders half was fixed first and the instances half was missed.
//
// These run the REAL query text, sliced out of the two repos, against real
// SQLite. A source-level check could not catch the failure that matters here:
// a typo in the `entity_type` literal turns the `NOT EXISTS` into a tautology,
// so every pending row is re-enqueued on every drain, unboundedly — and the
// source still reads as though it were correct.
// ── Part H: drain() always releases its re-entrancy flag ─────────────────
//
// `drain()` sets `this.draining = true` before its first await so two drains
// cannot both clear the guard. That flag is released in a `finally`, which
// means every exit after the claim must be INSIDE that try. It is easy to get
// wrong and the symptom is severe and silent: a `return` from outside it --
// the offline path -- leaves the flag set, every later drain short-circuits,
// and sync is dead for the life of the process. No SQL check can see this, so
// it is asserted structurally.
function partH() {
  section("Part H: drain() releases the re-entrancy flag on every path");

  const code = readFileSync(WORKER_TS, "utf8");
  const drain = body(code, "async drain(");
  const scan = blankLiterals(drain);

  const matchBrace = (open) => {
    let depth = 0;
    for (let i = open; i < scan.length; i += 1) {
      if (scan[i] === "{") depth += 1;
      else if (scan[i] === "}") {
        depth -= 1;
        if (depth === 0) return i;
      }
    }
    return -1;
  };

  const claim = scan.indexOf("this.draining = true;");
  ok("drain() claims the flag", claim !== -1);

  // The projection-repair assertions that used to sit here checked that the
  // drain ran `medicationMirror.repairMissingProjections()` before the dose-log
  // one. Both mirrors went with the legacy generation, and with a single
  // generation of tables there is nothing left to project between.
  //
  // The reconcile ordering below is the surviving instance of the same rule and
  // is still asserted: an instance's push resolves its parent, so the parent's
  // queue entry has to be created first.

  // Ordering, not presence. The previous check here was `claim !== -1` under a
  // label that promised ordering, so inserting an `await` above the claim --
  // reintroducing precisely the race the claim exists to prevent -- passed.
  // Claiming and releasing a flag nobody reads is theatre: two drains then run
  // concurrently over the same queue snapshot, which is the race the guard was
  // added for. Deleting the short-circuit is caught by `tsc` only while the
  // field has no other reader, which is not a property to lean on.
  ok(
    "drain() short-circuits on the flag before claiming it",
    /if\s*\(\s*this\.draining\s*\)\s*\{?\s*return\b/.test(
      scan.slice(0, claim === -1 ? 0 : claim)
    )
  );

  const firstAwait = scan.search(/\bawait\b/);
  ok(
    "drain() claims the flag before its first await",
    claim !== -1 && firstAwait !== -1 && claim < firstAwait,
    `\n    claim at ${claim}, first await at ${firstAwait}`
  );

  // Every try in the body, brace-matched, paired with its own `finally`.
  //
  // The previous version took "the first `try` after the claim" as the guarded
  // region. That is a shape, not the invariant, and one edit defeated all of
  // it: closing a decoy try around the connectivity block and reopening a
  // second try for the real work left every check reading the decoy, while the
  // offline `return` escaped through a try that had only a `catch`. The flag
  // latched for the process lifetime and the suite stayed green. So the guard
  // is now identified by what it does -- releasing the flag in a finally -- and
  // every exit is tested for containment within it.
  const tries = [];
  for (const m of scan.matchAll(/\btry\b/g)) {
    const open = scan.indexOf("{", m.index);
    const close = matchBrace(open);
    if (open === -1 || close === -1) continue;
    let cursor = close + 1;
    let fin = null;
    for (;;) {
      const rest = scan.slice(cursor);
      const mFinally = /^\s*finally\s*\{/.exec(rest);
      if (mFinally) {
        const fOpen = cursor + mFinally[0].length - 1;
        const fClose = matchBrace(fOpen);
        fin = { open: fOpen, close: fClose };
        cursor = fClose + 1;
        break;
      }
      const mCatch = /^\s*catch\s*(\([^)]*\)\s*)?\{/.exec(rest);
      if (mCatch) {
        cursor = matchBrace(cursor + mCatch[0].length - 1) + 1;
        continue;
      }
      break;
    }
    tries.push({ open, close, end: cursor, fin });
  }

  const releasing = tries.filter(
    (t) =>
      t.fin &&
      /this\.draining\s*=\s*false\s*;/.test(scan.slice(t.fin.open, t.fin.close))
  );
  ok(
    "exactly one try releases the flag in a finally",
    releasing.length === 1,
    `\n    found ${releasing.length}`
  );
  const guard = releasing[0];

  // Unconditionally. `finally { if (this.status !== "error") this.draining =
  // false; }` satisfies any "the finally mentions the release" pattern while
  // leaking the flag on every errored drain.
  const finallyBody = guard
    ? scan.slice(guard.fin.open + 1, guard.fin.close)
    : "";
  ok(
    "that finally releases the flag unconditionally",
    guard !== undefined &&
      finallyBody.replace(/this\.draining\s*=\s*false\s*;/, "").trim() === "",
    `\n    finally body: ${finallyBody.trim()}`
  );

  // The containment invariant itself: after the flag is claimed, no exit may
  // bypass the releasing finally.
  const returns = [...scan.matchAll(/\breturn\b/g)]
    .map((m) => m.index)
    .filter((i) => i > claim);
  // Bounded by `end` (past the catch and finally clauses), not `close` (the try
  // block's own brace). A `return` in the guarded try's `catch` sits between the
  // two and still runs the `finally`, so bounding by `close` would report
  // correct code as escaping the moment anyone adds one.
  const escaping = guard
    ? returns.filter((i) => !(i > guard.open && i < guard.end))
    : returns;
  ok(
    "every return after the claim is inside the releasing try",
    guard !== undefined && escaping.length === 0,
    `\n    ${escaping.length} return(s) escape the guard`
  );

  // `throw` is here because `return` and `await` are only two of the three ways
  // control leaves a function. A `throw` between the claim and the guarded try
  // -- `if (this.status === "offline") throw new Error(...)` -- escapes without
  // ever entering the `finally`, wedging the flag on for the life of the
  // process: exactly the failure this whole part exists to prevent, and it
  // passed every check here.
  const ESCAPES = /\b(return|await|throw)\b/;

  const beforeTry = guard ? scan.slice(claim, guard.open) : "";
  ok(
    "nothing returns, awaits or throws between the claim and the releasing try",
    guard !== undefined && !ESCAPES.test(beforeTry),
    `\n    found: ${beforeTry.trim()}`
  );

  const afterGuard = guard ? scan.slice(guard.end) : "";
  ok(
    "nothing returns, awaits or throws after the releasing try",
    guard !== undefined && !ESCAPES.test(afterGuard),
    `\n    found: ${afterGuard.trim()}`
  );

  // Content pins for the two exits that historically escaped. Sliced from the
  // original text, not the blanked copy, because these match string contents.
  const guarded = guard ? drain.slice(guard.open, guard.close) : "";
  ok(
    "the offline early-return is inside the releasing try",
    /setStatus\("offline"\)/.test(guarded)
  );
  ok(
    "the connectivity-failure exit is inside the releasing try",
    /connectivity check failed/.test(guarded)
  );
}

function partG() {
  section("Part G: reconcilePending selects exactly the unqueued pending rows");

  const remindersSrc = readFileSync(REMINDERS_TS, "utf8");
  const instancesSrc = readFileSync(INSTANCES_TS, "utf8");
  const reminderQuery = template(
    remindersSrc,
    "export async function reconcilePending("
  );
  const instanceQuery = template(
    instancesSrc,
    "export async function reconcilePending("
  );

  const db = open();
  applyMigrations(db, ALL);

  const mkReminder = db.prepare(
    `INSERT INTO reminders (id, user_id, kind, name, schedule, created_at,
       updated_at, sync_status, convex_id)
     VALUES (?, ?, 'medication', ?, '{"type":"daily","times":[]}', 1, 1, ?, ?)`
  );
  const mkInstance = db.prepare(
    `INSERT INTO reminder_instances (id, reminder_id, user_id, reminder_kind,
       scheduled_time, status, created_at, updated_at, sync_status, convex_id)
     VALUES (?, ?, 'u1', 'medication', 1, 'pending', 1, 1, ?, ?)`
  );
  // `sync_queue.id` is an INTEGER rowid; let SQLite assign it.
  const mkQueue = db.prepare(
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload,
       attempts, created_at, dead_letter)
     VALUES (?, ?, 'create', '{}', 0, 1, ?)`
  );

  // r-backfill: what 005+007 leave behind — pending, nothing queued. MUST be picked up.
  // r-queued:   already has a live entry. MUST NOT be re-enqueued (that is the duplicate bug).
  // r-synced:   done. MUST NOT be touched.
  // r-dead:     its only entry is dead-lettered, and `markSourceError` set it 'error',
  //             so the status filter excludes it. Documents the real behaviour.
  mkReminder.run("r-backfill", "u1", "Backfilled", "pending", null);
  mkReminder.run("r-queued", "u1", "Queued", "pending", null);
  mkReminder.run("r-synced", "u1", "Synced", "synced", "rem_real");
  mkReminder.run("r-dead", "u1", "Dead", "error", null);
  // r-revive:  pending, and its ONLY entry is dead-lettered. `markSourceError`
  //            swallows its own failures while `markDeadLettered` still runs,
  //            so this state is reachable. It MUST be picked up -- that is what
  //            `dead_letter = 0` buys, and without the clause it is skipped
  //            forever. The `r-dead` row above cannot test this: its status is
  //            'error', so the status filter excludes it first.
  mkReminder.run("r-revive", "u1", "Revive", "pending", null);
  mkQueue.run("reminder", "r-queued", 0);
  mkQueue.run("reminder", "r-dead", 1);
  mkQueue.run("reminder", "r-revive", 1);

  const picked = db
    .prepare(reminderQuery)
    .all()
    .map((r) => r.id)
    .sort();
  eq("reminder reconcile picks only the unqueued pending rows", picked, [
    "r-backfill",
    "r-revive",
  ]);

  mkInstance.run("i-backfill", "r-synced", "pending", null);
  mkInstance.run("i-queued", "r-synced", "pending", null);
  mkInstance.run("i-synced", "r-synced", "synced", "inst_real");
  mkInstance.run("i-revive", "r-synced", "pending", null);
  mkQueue.run("reminder_instance", "i-queued", 0);
  mkQueue.run("reminder_instance", "i-revive", 1);

  const pickedI = db
    .prepare(instanceQuery)
    .all()
    .map((r) => r.id)
    .sort();
  eq("instance reconcile picks only the unqueued pending rows", pickedI, [
    "i-backfill",
    "i-revive",
  ]);

  // The tautology guard. A reminder entry must not satisfy an instance's
  // NOT EXISTS, or the two entity types would mask each other.
  mkInstance.run("i-crosstalk", "r-synced", "pending", null);
  mkQueue.run("reminder", "i-crosstalk", 0);
  const crosstalk = db
    .prepare(instanceQuery)
    .all()
    .map((r) => r.id)
    .sort();
  ok(
    "a `reminder` queue entry does not suppress a `reminder_instance` row",
    crosstalk.includes("i-crosstalk"),
    `\n    got ${JSON.stringify(crosstalk)}`
  );

  db.close();
}

function partF() {
  section("Part F: 007 clears cross-table convex ids");

  const db = open();
  applyMigrations(db, [
    "001_init",
    "002_add_convex_id",
    "003_sync_queue_dead_letter",
    "004_unified_reminders",
  ]);
  seed(db);
  applyMigrations(db, ["005_backfill_reminders", "006_widen_sync_queue"]);

  // Reproduce what the OLD 005 left behind: the projections carrying the
  // source rows' sync identity. This is the on-disk state of every device that
  // upgraded before the fix.
  db.exec(
    `UPDATE reminders SET
       convex_id   = (SELECT m.convex_id   FROM medications m WHERE m.id = reminders.id),
       sync_status = (SELECT m.sync_status FROM medications m WHERE m.id = reminders.id)`
  );
  db.exec(
    `UPDATE reminder_instances SET
       convex_id   = (SELECT d.convex_id   FROM dose_logs d WHERE d.id = reminder_instances.id),
       sync_status = (SELECT d.sync_status FROM dose_logs d WHERE d.id = reminder_instances.id)`
  );

  const reminder = (id) =>
    db.prepare("SELECT * FROM reminders WHERE id = ?").get(id);
  const instance = (id) =>
    db.prepare("SELECT * FROM reminder_instances WHERE id = ?").get(id);

  eq("pre-007: contaminated", reminder("med-aaaa-1111")?.convex_id, "cx_med_1");

  // Pinned to a state a correct 007 must preserve *in full*. Asserting only
  // `convex_id === null` on a row that was already NULL holds under any
  // implementation -- including one whose UPDATE never runs, and one whose
  // WHERE is wide enough to re-pend rows it did not clear. Capturing the whole
  // row makes over-reach visible.
  db.prepare(
    "UPDATE reminders SET convex_id = NULL, sync_status = 'synced' WHERE id = 'med-bbbb-2222'"
  ).run();
  const untouched = JSON.stringify(reminder("med-bbbb-2222"));

  applyMigrations(db, ["007_clear_projected_convex_ids"]);

  eq("007 clears a medication id", reminder("med-aaaa-1111")?.convex_id, null);
  eq("007 clears another", reminder("med-cccc-3333")?.convex_id, null);
  eq(
    "007 re-pends the cleared row",
    reminder("med-aaaa-1111")?.sync_status,
    "pending"
  );
  eq(
    "007 leaves an already-NULL row completely alone",
    JSON.stringify(reminder("med-bbbb-2222")),
    untouched
  );
  eq("007 clears a dose log id", instance("dose-1")?.convex_id, null);
  eq(
    "007 re-pends the cleared instance",
    instance("dose-1")?.sync_status,
    "pending"
  );

  // The idempotency requirement, and the reason the guard matches on the
  // *value* rather than on "this row came from 005". Once a reminder has
  // genuinely synced it holds an `Id<"reminders">`, which can never equal the
  // `Id<"medications">` on the medication of the same local id — so a re-run
  // must not touch it. A cruder `id IN (SELECT id FROM medications)` guard
  // would discard it here and force a duplicate create on the server.
  db.prepare(
    "UPDATE reminders SET convex_id = 'rem_real_1', sync_status = 'synced' WHERE id = 'med-aaaa-1111'"
  ).run();
  db.exec(sql("007_clear_projected_convex_ids"));
  eq(
    "re-running 007 spares a genuinely resolved reminder id",
    reminder("med-aaaa-1111")?.convex_id,
    "rem_real_1"
  );
  eq(
    "re-running 007 does not re-pend it",
    reminder("med-aaaa-1111")?.sync_status,
    "synced"
  );

  // And a re-run over already-cleared rows changes nothing.
  const before = JSON.stringify(reminder("med-cccc-3333"));
  db.exec(sql("007_clear_projected_convex_ids"));
  eq(
    "re-running 007 over cleared rows is a no-op",
    JSON.stringify(reminder("med-cccc-3333")),
    before
  );

  db.close();
}

/**
 * Part J — a device edit must not overwrite what only the server can author.
 *
 * `priority`, `tags` and `caregiverIds` still have no local author. Nothing on
 * the device sets them: `remindersRepo.create` has callers now, but neither
 * `meds/new.tsx` nor the stress-seed screen supplies any of the three, and a
 * row backfilled by migration 005 carries 004's column defaults. The columns
 * are therefore `normal`/`[]`/`[]`, and `api.reminders.update` patches every
 * field it is handed — so pushing the column reset the server's copy. Not only
 * on edit: a row left `pending` is re-enqueued by `reconcilePending`, which
 * re-sent the same reset on later drains, and `reminders.create`'s collision
 * branch defaulted them a second time on any replayed create.
 *
 * The original writer this guarded was the medication mirror, which omitted all
 * three from its conflict clause. That module is deleted; the hazard is not,
 * because the columns are still unauthored on this device.
 *
 * These checks are static, and that is not a compromise. The failure needs a
 * *second* writer — a web client, another device — to be observable at all, so
 * no amount of on-device testing can produce the input. The payload shape is
 * the thing to pin, and it is pinned on both sides of the wire.
 */
function partJ() {
  section(
    "Part J: the push path does not overwrite server-owned reminder fields"
  );

  const strip = (src) =>
    src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const count = (haystack, re) => (haystack.match(re) ?? []).length;

  const SERVER_OWNED = ["priority", "tags", "caregiverIds"];
  // The medication genuinely is the source of truth for these, so they must
  // keep flowing. Without this half, deleting the payload entirely would pass.
  const DEVICE_OWNED = ["name", "schedule", "active"];

  // 1. The repo is where the null is decided.
  const repo = strip(readFileSync(REMINDERS_TS, "utf8"));
  const external = body(repo, "export async function enqueueExternalWrite(");
  for (const field of SERVER_OWNED) {
    ok(
      `enqueueExternalWrite sends ${field} as null`,
      new RegExp(`${field}:\\s*null`).test(external)
    );
    ok(
      `enqueueExternalWrite does not read ${field} off the row`,
      !new RegExp(`${field}:\\s*existing\\.`).test(external)
    );
  }
  for (const field of DEVICE_OWNED) {
    ok(
      `enqueueExternalWrite still sends ${field} from the row`,
      new RegExp(`${field}:\\s*existing\\.`).test(external)
    );
  }

  // 2. The worker passes none of them unguarded, on either branch.
  const worker = strip(readFileSync(WORKER_TS, "utf8"));
  const reminderEntry = body(worker, "private async processReminderEntry(");
  for (const field of SERVER_OWNED) {
    const guards = count(
      reminderEntry,
      new RegExp(
        `\\.\\.\\.\\(payload\\.${field}\\s*!==\\s*null\\s*\\?\\s*\\{\\s*${field}:\\s*payload\\.${field}\\s*\\}\\s*:\\s*\\{\\}\\)`,
        "g"
      )
    );
    ok(
      `both reminder branches guard ${field}`,
      guards === 2,
      `\n    ${guards} guards, expected 2 (create and update)`
    );
    // Each guard names the field twice and nothing else may. A bare
    // `${field}: payload.${field}` put back anywhere in either branch pushes
    // the count past 2x and fails here rather than silently on a user's data.
    const mentions = count(
      reminderEntry,
      new RegExp(`payload\\.${field}\\b`, "g")
    );
    ok(
      `no unguarded use of payload.${field}`,
      mentions === guards * 2,
      `\n    ${mentions} mentions for ${guards} guards`
    );
  }
  for (const field of DEVICE_OWNED) {
    const bare = count(
      reminderEntry,
      new RegExp(`\\n\\s*${field}:\\s*payload\\.${field},`, "g")
    );
    ok(
      `both reminder branches still send ${field} unguarded`,
      bare === 2,
      `\n    found ${bare}, expected 2`
    );
  }

  // 3. The server's own upsert stops defaulting them over a live document.
  const convex = strip(readFileSync(CONVEX_REMINDERS_TS, "utf8"));
  for (const [field, fallback] of [
    ["priority", '"normal"'],
    ["tags", "\\[\\]"],
    ["caregiverIds", "\\[\\]"],
  ]) {
    const defaulted = count(
      convex,
      new RegExp(`${field}:\\s*args\\.${field}\\s*\\?\\?\\s*${fallback}`, "g")
    );
    ok(
      `reminders.create defaults ${field} on insert only`,
      defaulted === 1,
      `\n    found ${defaulted}, expected 1 (the insert)`
    );
    const guards = count(
      convex,
      new RegExp(
        `\\.\\.\\.\\(args\\.${field}\\s*!==\\s*undefined\\s*\\?\\s*\\{\\s*${field}:\\s*args\\.${field}\\s*\\}\\s*:\\s*\\{\\}\\)`,
        "g"
      )
    );
    ok(
      `an omitted ${field} is left alone by both the collision patch and update`,
      guards === 2,
      `\n    found ${guards}, expected 2 (create-collision and update)`
    );
  }
}

// ── Part K: 009 drops the legacy generation ───────────────────────────────
//
// The end of the cutover, and the one migration that DESTROYS rather than adds.
// Three things have to hold at once, and only the first is obvious:
//
//   * the legacy tables are gone;
//   * everything unified survives untouched — the drop must not take the data
//     the whole cutover existed to move;
//   * `sync_queue` keeps its unified entries but loses the legacy ones, and its
//     CHECK narrows so no new legacy entry can be written.
//
// The queue deletion is the subtle one. A leftover 'medication' entry addresses
// a Convex function that no longer exists, so draining it could only burn five
// attempts and dead-letter — noise that looks like a sync fault. It is deleted
// rather than left, and this pins that.
function partK() {
  section("Part K — 009 drops the legacy generation");

  const db = open();
  applyMigrations(db, THROUGH_008);

  // A device mid-cutover: legacy rows, unified rows, and queue entries of all
  // four kinds.
  db.prepare(
    `INSERT INTO medications (id, user_id, name, schedule_type, schedule_times,
       active, created_at, updated_at)
     VALUES ('med-1', 'u1', 'Metformin', 'daily', '{"times":["09:00"]}', 1, 1, 1)`
  ).run();
  db.prepare(
    `INSERT INTO dose_logs (id, user_id, medication_id, scheduled_time, status,
       created_at, updated_at)
     VALUES ('dose-1', 'u1', 'med-1', 100, 'taken', 1, 1)`
  ).run();
  db.prepare(
    `INSERT INTO reminders (id, user_id, kind, name, schedule, created_at,
       updated_at, sync_status)
     VALUES ('rem-1', 'u1', 'medication', 'Metformin',
       '{"type":"daily","times":["09:00"]}', 1, 1, 'synced')`
  ).run();
  db.prepare(
    `INSERT INTO reminder_instances (id, reminder_id, user_id, reminder_kind,
       scheduled_time, status, taken_at, created_at, updated_at, sync_status)
     VALUES ('inst-1', 'rem-1', 'u1', 'medication', 100, 'taken', 150, 1, 1, 'synced')`
  ).run();

  const enqueue = db.prepare(
    `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
     VALUES (?,?,?,?,?)`
  );
  enqueue.run("medication", "med-1", "create", "{}", 1);
  enqueue.run("dose_log", "dose-1", "create", "{}", 2);
  enqueue.run("reminder", "rem-1", "create", "{}", 3);
  enqueue.run("reminder_instance", "inst-1", "create", "{}", 4);

  const seqBefore = db
    .prepare("SELECT seq FROM sqlite_sequence WHERE name = 'sync_queue'")
    .get();

  eq("009 is the only migration left to apply", applyMigrations(db, ALL), [
    "009_drop_legacy_generation",
  ]);

  const tables = db
    .prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
    )
    .all()
    .map((row) => row.name)
    .filter((name) => !name.startsWith("sqlite_"));
  eq("only the unified generation remains", tables, [
    "_migrations",
    "reminder_instances",
    "reminders",
    "sync_queue",
  ]);

  // The point of the whole cutover: the unified rows are still here, with their
  // values, not merely their table.
  eq(
    "the reminder survives with its schedule intact",
    db.prepare("SELECT id, name, schedule FROM reminders").all(),
    [
      {
        id: "rem-1",
        name: "Metformin",
        schedule: '{"type":"daily","times":["09:00"]}',
      },
    ]
  );
  eq(
    "the taken instance survives, status and timestamp intact",
    db
      .prepare(
        "SELECT id, reminder_id, status, taken_at FROM reminder_instances"
      )
      .all(),
    [{ id: "inst-1", reminder_id: "rem-1", status: "taken", taken_at: 150 }]
  );

  eq(
    "unified queue entries survive, legacy ones are deleted",
    db
      .prepare(
        "SELECT entity_type, entity_id FROM sync_queue ORDER BY created_at"
      )
      .all(),
    [
      { entity_type: "reminder", entity_id: "rem-1" },
      { entity_type: "reminder_instance", entity_id: "inst-1" },
    ]
  );

  const tryInsert = (entityType) => {
    try {
      db.prepare(
        `INSERT INTO sync_queue (entity_type, entity_id, operation, payload, created_at)
         VALUES (?,?,?,?,?)`
      ).run(entityType, "x", "create", "{}", 9);
      return null;
    } catch (e) {
      return e;
    }
  };
  ok(
    "the narrowed CHECK rejects 'medication'",
    tryInsert("medication") !== null
  );
  ok("the narrowed CHECK rejects 'dose_log'", tryInsert("dose_log") !== null);
  ok("'reminder' is still accepted", tryInsert("reminder") === null);

  // The rebuild carries the AUTOINCREMENT watermark, as 006's did. Without it
  // the queue reuses ids a drained entry once held.
  const seqAfter = db
    .prepare("SELECT seq FROM sqlite_sequence WHERE name = 'sync_queue'")
    .get();
  ok(
    "the AUTOINCREMENT watermark is not rewound by the rebuild",
    seqBefore !== undefined &&
      seqAfter !== undefined &&
      seqAfter.seq >= seqBefore.seq,
    `before ${JSON.stringify(seqBefore)}, after ${JSON.stringify(seqAfter)}`
  );

  eq("re-running 009 is a no-op", applyMigrations(db, ALL), []);

  db.close();
}

// ── run ───────────────────────────────────────────────────────────────────

console.log(`node ${process.version}, node:sqlite`);
part0();
partA();
partD(partC(partB()));
partF();
partG();
partH();
partJ();
partK();

console.log(
  `\n${checks - failures}/${checks} checks passed${failures === 0 ? "" : `, ${failures} FAILED`}`
);
process.exit(failures === 0 ? 0 : 1);
