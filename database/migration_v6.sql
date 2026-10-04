-- C12 creative statistics. Automatic Hibernate schema updates add this column too.
-- Nullable preserves existing books; no historical goals or activity are invented.
ALTER TABLE writing_books ADD COLUMN IF NOT EXISTS stats_data TEXT;
