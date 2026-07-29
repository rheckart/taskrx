import * as SQLite from "expo-sqlite";
import type { SQLiteDatabase } from "expo-sqlite";

/**
 * Local SQLite database for the TaskRx POC.
 *
 * This module owns the singleton database connection and the migration
 * runner. All repositories (`apps/mobile/src/db/repos/*`) call `getDb()`
 * to obtain the connection; they never open their own.
 *
 * The database is the source of truth on the device. Convex is the source
 * of truth in the cloud; the sync layer (Day 7) drains `sync_queue` to push
 * local changes up and merges remote changes down.
 */

const DATABASE_NAME = "taskrx.db";

interface Migration {
  readonly name: string;
  readonly sql: string;
}

/**
 * Migration files live in `apps/mobile/src/db/migrations/`. The SQL is
 * inlined here so the migration runner does not need a filesystem read at
 * boot — keeping boot fast and avoiding a dependency on `expo-file-system`.
 *
 * When adding a new migration:
 *   1. Create `00N_description.sql` in the migrations directory.
 *   2. Add a new entry below with the same SQL.
 *   3. The runner will apply it on next boot (tracked in `_migrations`).
 */
const MIGRATIONS: readonly Migration[] = [
  {
    name: "001_init",
    sql: `
      CREATE TABLE IF NOT EXISTS medications (
        id              TEXT PRIMARY KEY,
        user_id         TEXT NOT NULL,
        name            TEXT NOT NULL,
        rxcui           TEXT,
        dosage          TEXT,
        schedule_type   TEXT NOT NULL CHECK (schedule_type IN ('daily', 'interval')),
        schedule_times  TEXT NOT NULL,
        active          INTEGER NOT NULL DEFAULT 1,
        created_at      INTEGER NOT NULL,
        updated_at      INTEGER NOT NULL,
        sync_status     TEXT NOT NULL DEFAULT 'pending' CHECK (sync_status IN ('pending','synced','error'))
      );
      CREATE INDEX IF NOT EXISTS idx_medications_user ON medications(user_id);

      CREATE TABLE IF NOT EXISTS dose_logs (
        id              TEXT PRIMARY KEY,
        user_id         TEXT NOT NULL,
        medication_id   TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
        scheduled_time  INTEGER NOT NULL,
        taken_at        INTEGER,
        status          TEXT NOT NULL CHECK (status IN ('pending','taken','skipped','missed')),
        notes           TEXT,
        created_at      INTEGER NOT NULL,
        updated_at      INTEGER NOT NULL,
        sync_status     TEXT NOT NULL DEFAULT 'pending'
      );
      CREATE INDEX IF NOT EXISTS idx_dose_logs_medication ON dose_logs(medication_id);
      CREATE INDEX IF NOT EXISTS idx_dose_logs_user_time ON dose_logs(user_id, scheduled_time);

      CREATE TABLE IF NOT EXISTS sync_queue (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        entity_type     TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log')),
        entity_id       TEXT NOT NULL,
        operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
        payload         TEXT NOT NULL,
        attempts        INTEGER NOT NULL DEFAULT 0,
        last_error      TEXT,
        created_at      INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
    `,
  },
  {
    name: "002_add_convex_id",
    sql: `
      ALTER TABLE medications ADD COLUMN convex_id TEXT;
      ALTER TABLE dose_logs ADD COLUMN convex_id TEXT;
      CREATE INDEX IF NOT EXISTS idx_medications_convex_id ON medications(convex_id);
      CREATE INDEX IF NOT EXISTS idx_dose_logs_convex_id ON dose_logs(convex_id);
    `,
  },
  {
    name: "003_sync_queue_dead_letter",
    sql: `
      ALTER TABLE sync_queue ADD COLUMN dead_letter INTEGER NOT NULL DEFAULT 0;
      CREATE INDEX IF NOT EXISTS idx_sync_queue_dead_letter ON sync_queue(dead_letter);
    `,
  },
  {
    name: "004_unified_reminders",
    sql: `
      CREATE TABLE IF NOT EXISTS reminders (
        id                    TEXT PRIMARY KEY,
        user_id               TEXT NOT NULL,
        kind                  TEXT NOT NULL CHECK (kind IN ('medication', 'task')),
        name                  TEXT NOT NULL,
        rxcui                 TEXT,
        dosage                TEXT,
        instructions          TEXT,
        schedule              TEXT NOT NULL,
        priority              TEXT NOT NULL DEFAULT 'normal'
                                CHECK (priority IN ('critical', 'high', 'normal', 'low')),
        tags                  TEXT NOT NULL DEFAULT '[]',
        pills_remaining       INTEGER,
        pills_per_dose        REAL,
        refill_reminder_days  INTEGER,
        caregiver_ids         TEXT NOT NULL DEFAULT '[]',
        active                INTEGER NOT NULL DEFAULT 1,
        created_at            INTEGER NOT NULL,
        updated_at            INTEGER NOT NULL,
        sync_status           TEXT NOT NULL DEFAULT 'pending'
                                CHECK (sync_status IN ('pending', 'synced', 'error')),
        convex_id             TEXT
      );
      CREATE INDEX IF NOT EXISTS idx_reminders_user_active ON reminders(user_id, active);
      CREATE INDEX IF NOT EXISTS idx_reminders_user_kind ON reminders(user_id, kind);
      CREATE INDEX IF NOT EXISTS idx_reminders_convex_id ON reminders(convex_id);

      CREATE TABLE IF NOT EXISTS reminder_instances (
        id              TEXT PRIMARY KEY,
        reminder_id     TEXT NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
        user_id         TEXT NOT NULL,
        reminder_kind   TEXT NOT NULL CHECK (reminder_kind IN ('medication', 'task')),
        scheduled_time  INTEGER NOT NULL,
        status          TEXT NOT NULL
                          CHECK (status IN ('pending', 'taken', 'snoozed', 'skipped', 'missed')),
        taken_at        INTEGER,
        snoozed_until   INTEGER,
        snooze_count    INTEGER NOT NULL DEFAULT 0,
        notes           TEXT,
        created_at      INTEGER NOT NULL,
        updated_at      INTEGER NOT NULL,
        sync_status     TEXT NOT NULL DEFAULT 'pending'
                          CHECK (sync_status IN ('pending', 'synced', 'error')),
        convex_id       TEXT
      );
      CREATE INDEX IF NOT EXISTS idx_reminder_instances_reminder
        ON reminder_instances(reminder_id);
      CREATE INDEX IF NOT EXISTS idx_reminder_instances_user_scheduled
        ON reminder_instances(user_id, scheduled_time);
      CREATE INDEX IF NOT EXISTS idx_reminder_instances_convex_id
        ON reminder_instances(convex_id);
      CREATE INDEX IF NOT EXISTS idx_reminder_instances_pending
        ON reminder_instances(user_id, scheduled_time)
        WHERE status = 'pending';
    `,
  },
  {
    name: "005_backfill_reminders",
    sql: `
      INSERT OR IGNORE INTO reminders (
        id, user_id, kind, name, rxcui, dosage, instructions, schedule,
        priority, tags, pills_remaining, pills_per_dose, refill_reminder_days,
        caregiver_ids, active, created_at, updated_at, sync_status, convex_id
      )
      SELECT
        src.id,
        src.user_id,
        'medication',
        src.name,
        src.rxcui,
        src.dosage,
        NULL,
        CASE src.schedule_type
          WHEN 'interval' THEN json_object(
            'type', 'interval',
            'hours', CASE
              WHEN json_type(src.sched, '$.hours') IN ('integer', 'real')
                THEN json_extract(src.sched, '$.hours')
              ELSE 0
            END,
            'startAt', CASE
              WHEN json_type(src.sched, '$.startAt') IN ('integer', 'real')
                THEN json_extract(src.sched, '$.startAt')
              ELSE 0
            END
          )
          ELSE json_object(
            'type', 'daily',
            'times', json(CASE
              WHEN json_type(src.sched, '$.times') = 'array'
                THEN json_extract(src.sched, '$.times')
              ELSE '[]'
            END)
          )
        END,
        'normal',
        '[]',
        NULL,
        NULL,
        NULL,
        '[]',
        src.active,
        src.created_at,
        src.updated_at,
        'pending',
        NULL
      FROM (
        SELECT
          m.*,
          CASE WHEN json_valid(m.schedule_times) THEN m.schedule_times ELSE '{}' END AS sched
        FROM medications m
      ) AS src;

      INSERT OR IGNORE INTO reminder_instances (
        id, reminder_id, user_id, reminder_kind, scheduled_time,
        status, taken_at, snoozed_until, snooze_count, notes,
        created_at, updated_at, sync_status, convex_id
      )
      SELECT
        d.id,
        d.medication_id,
        d.user_id,
        'medication',
        d.scheduled_time,
        d.status,
        d.taken_at,
        NULL,
        0,
        d.notes,
        d.created_at,
        d.updated_at,
        'pending',
        NULL
      FROM dose_logs d
      WHERE EXISTS (SELECT 1 FROM reminders r WHERE r.id = d.medication_id);
    `,
  },
  {
    name: "006_widen_sync_queue",
    sql: `
      DROP TABLE IF EXISTS sync_queue_006;

      CREATE TABLE sync_queue_006 (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        entity_type     TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log','reminder','reminder_instance')),
        entity_id       TEXT NOT NULL,
        operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
        payload         TEXT NOT NULL,
        attempts        INTEGER NOT NULL DEFAULT 0,
        last_error      TEXT,
        created_at      INTEGER NOT NULL,
        dead_letter     INTEGER NOT NULL DEFAULT 0
      );

      INSERT INTO sqlite_sequence (name, seq)
      SELECT 'sync_queue_006', seq FROM sqlite_sequence WHERE name = 'sync_queue';

      INSERT INTO sync_queue_006 (
        id, entity_type, entity_id, operation, payload,
        attempts, last_error, created_at, dead_letter
      )
      SELECT
        id, entity_type, entity_id, operation, payload,
        attempts, last_error, created_at, dead_letter
      FROM sync_queue;

      DROP TABLE sync_queue;
      ALTER TABLE sync_queue_006 RENAME TO sync_queue;

      CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
      CREATE INDEX IF NOT EXISTS idx_sync_queue_dead_letter ON sync_queue(dead_letter);
    `,
  },
  {
    name: "007_clear_projected_convex_ids",
    sql: `
      UPDATE reminders
      SET convex_id   = NULL,
          sync_status = 'pending'
      WHERE convex_id IS NOT NULL
        AND convex_id = (
          SELECT m.convex_id FROM medications m WHERE m.id = reminders.id
        );

      UPDATE reminder_instances
      SET convex_id   = NULL,
          sync_status = 'pending'
      WHERE convex_id IS NOT NULL
        AND convex_id = (
          SELECT d.convex_id FROM dose_logs d WHERE d.id = reminder_instances.id
        );
    `,
  },
];

let dbPromise: Promise<SQLiteDatabase> | null = null;

/**
 * Returns the singleton SQLite database connection, opening it and running
 * any pending migrations on first call. Subsequent calls return the same
 * promise (and therefore the same connection).
 *
 * Safe to call from React effects, navigation handlers, or background
 * tasks. The first caller pays the migration cost; everyone else awaits
 * the same in-flight promise.
 */
export function getDb(): Promise<SQLiteDatabase> {
  dbPromise ??= openAndMigrate();
  return dbPromise;
}

async function openAndMigrate(): Promise<SQLiteDatabase> {
  const db = await SQLite.openDatabaseAsync(DATABASE_NAME);
  await runMigrations(db);
  return db;
}

async function runMigrations(db: SQLiteDatabase): Promise<void> {
  await db.execAsync(
    "CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL);"
  );

  for (const migration of MIGRATIONS) {
    const applied = await db.getFirstAsync<{ name: string }>(
      "SELECT name FROM _migrations WHERE name = ?",
      migration.name
    );
    if (applied !== null) {
      continue;
    }

    await db.withTransactionAsync(async () => {
      await db.execAsync(migration.sql);
      await db.runAsync(
        "INSERT INTO _migrations (name, applied_at) VALUES (?, ?)",
        migration.name,
        Date.now()
      );
    });
  }
}

/**
 * Test-only: reset the singleton so the next `getDb()` call reopens the
 * database. Not exported from the package barrel; only used by tests.
 */
export function __resetDbForTests(): void {
  dbPromise = null;
}
