-- Incremented whenever a global account credential is changed or reset.
-- Access tokens carry the version observed at issuance; a mismatch revokes them immediately.
ALTER TABLE users
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 AFTER last_login_at;
