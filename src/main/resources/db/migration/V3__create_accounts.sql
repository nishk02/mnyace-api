CREATE TABLE accounts
(
    id              TEXT      NOT NULL PRIMARY KEY,
    user_id         TEXT      NOT NULL REFERENCES users (id),
    name            TEXT      NOT NULL,
    type            TEXT      NOT NULL CHECK (type IN ('CASH', 'BANK', 'WALLET')),
    current_balance REAL      NOT NULL DEFAULT 0,
    currency        TEXT      NOT NULL DEFAULT 'INR',
    color_label     TEXT,
    -- last 3-4 digits of the bank account, and up to 5 four-digit card endings (comma-separated); BANK only
    account_suffix  TEXT CHECK (account_suffix IS NULL OR
                                (length(account_suffix) BETWEEN 3 AND 4 AND account_suffix NOT GLOB '*[^0-9]*')),
    card_suffixes   TEXT      NOT NULL DEFAULT '',
    display_order   INTEGER   NOT NULL DEFAULT 0,
    is_active       INTEGER   NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    UNIQUE (user_id, name),
    CHECK (type = 'BANK' OR (account_suffix IS NULL AND card_suffixes = ''))
);

CREATE INDEX idx_accounts_user ON accounts (user_id);

CREATE UNIQUE INDEX idx_accounts_one_cash_per_user ON accounts (user_id) WHERE type = 'CASH';
-- Deleting an account clears its digits, so a plain unique index is enough
CREATE UNIQUE INDEX idx_accounts_user_account_suffix ON accounts (user_id, account_suffix) WHERE account_suffix IS NOT NULL;
