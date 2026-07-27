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
