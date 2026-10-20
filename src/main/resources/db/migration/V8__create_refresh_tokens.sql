CREATE TABLE refresh_tokens
(
    id            TEXT        NOT NULL PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES users (id),
    token_hash    TEXT        NOT NULL UNIQUE,
    family_id     TEXT        NOT NULL,
    device_label  TEXT,
    expires_at    TIMESTAMP   NOT NULL,
    revoked_at    TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);