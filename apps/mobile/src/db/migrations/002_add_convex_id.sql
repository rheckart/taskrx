-- Migration 002: add convex_id columns for sync id mapping.
--
-- Convex generates its own document ids. The sync worker needs to map the
-- stable local UUIDs (medications.id / dose_logs.id) to the Convex ids
-- returned by create mutations so that updates/deletes target the right
-- remote document and dose logs reference the right remote medication.
ALTER TABLE medications ADD COLUMN convex_id TEXT;
ALTER TABLE dose_logs ADD COLUMN convex_id TEXT;

CREATE INDEX IF NOT EXISTS idx_medications_convex_id ON medications(convex_id);
CREATE INDEX IF NOT EXISTS idx_dose_logs_convex_id ON dose_logs(convex_id);
