CREATE TABLE password_resets
(
    id         TEXT      NOT NULL PRIMARY KEY,
    user_id    TEXT      NOT NULL REFERENCES users (id),
    code_hash  TEXT      NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts   INTEGER   NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    used_at    TIMESTAMP
);

CREATE INDEX idx_password_resets_user ON password_resets (user_id);
CREATE INDEX idx_password_resets_created ON password_resets (created_at);
