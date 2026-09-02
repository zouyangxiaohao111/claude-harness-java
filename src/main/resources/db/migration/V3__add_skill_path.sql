-- ===================================================================
-- V3: Add 'path' column to skills (file system location is source of truth)
-- v1 stored only name/description/enabled/builtin/config; v2 stores
-- absolute file system path to the skills directory + folder name.
-- ===================================================================

ALTER TABLE skills ADD COLUMN path TEXT;
