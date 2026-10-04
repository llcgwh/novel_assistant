-- C10 permanent local audit and explicitly confirmed remote maintenance.
-- No existing author data is changed; audit records deliberately have no cascading foreign key.
CREATE TABLE IF NOT EXISTS backup_operations (
    id BIGSERIAL PRIMARY KEY,
    novel_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    stage VARCHAR(60),
    message TEXT,
    error_type VARCHAR(160),
    http_status INTEGER,
    details TEXT
);
CREATE INDEX IF NOT EXISTS backup_operations_novel_id_id ON backup_operations(novel_id,id);
CREATE TABLE IF NOT EXISTS backup_retention (
    novel_id BIGINT PRIMARY KEY,
    keep_last INTEGER NOT NULL DEFAULT 20,
    keep_days INTEGER NOT NULL DEFAULT 30,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS backup_cleanups (
    token VARCHAR(36) PRIMARY KEY,
    novel_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_id VARCHAR(36),
    preview TEXT NOT NULL,
    result TEXT
);
