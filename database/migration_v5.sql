-- Idempotent editor submissions. Existing schemas using ddl-auto=update are updated automatically.
CREATE TABLE IF NOT EXISTS form_submissions (
    token VARCHAR(36) PRIMARY KEY,
    novel_id BIGINT NOT NULL,
    resource VARCHAR(40) NOT NULL,
    record_id BIGINT NOT NULL
);
