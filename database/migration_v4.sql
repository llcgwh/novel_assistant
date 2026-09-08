-- Run before upgrading when Hibernate schema updates are disabled.
-- Ciphertext includes a nonce, authentication tag and Base64 expansion.
ALTER TABLE novels ALTER COLUMN webdav_password TYPE TEXT;
