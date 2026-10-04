-- C08: shared immutable setting templates and novel-scoped editable copies.
ALTER TABLE writing_books ADD COLUMN IF NOT EXISTS series_data TEXT;

CREATE TABLE IF NOT EXISTS series_catalog_state (
  id BIGINT PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0
);
INSERT INTO series_catalog_state (id, version) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS series_templates (
  uid VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(20) NOT NULL,
  head_revision_uid VARCHAR(36) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  archived BOOLEAN NOT NULL DEFAULT FALSE,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS series_template_revisions (
  uid VARCHAR(36) PRIMARY KEY,
  template_uid VARCHAR(36) NOT NULL,
  payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_series_revision_template ON series_template_revisions(template_uid);

CREATE TABLE IF NOT EXISTS series_mutation_receipts (
  id VARCHAR(64) PRIMARY KEY,
  scope VARCHAR(120) NOT NULL,
  mutation_id VARCHAR(36) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  result TEXT NOT NULL
);
