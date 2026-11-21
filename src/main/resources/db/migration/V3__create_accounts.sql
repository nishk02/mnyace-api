CREATE TABLE accounts
(
    id              TEXT      NOT NULL PRIMARY KEY,
    user_id         TEXT      NOT NULL REFERENCES users (id),
    name            TEXT      NOT NULL,
    type            TEXT      NOT NULL CHECK (type IN ('CASH', 'BANK', 'WALLET')),
    current_balance REAL      NOT NULL DEFAULT 0,
    currency        TEXT      NOT NULL DEFAULT 'INR',
    -- a key from the app's fixed palette (see AccountPalette), for accounts whose colour is the user's pick;
    -- a listed bank's colour comes from the catalog instead, so it carries no key
    color_key       TEXT,
    -- last 3-4 digits of the bank account, and up to 5 four-digit card endings (comma-separated); BANK only
    account_suffix  TEXT CHECK (account_suffix IS NULL OR
                                (length(account_suffix) BETWEEN 3 AND 4 AND account_suffix NOT GLOB '*[^0-9]*')),
    card_suffixes   TEXT      NOT NULL DEFAULT '',
    -- a code from the bank catalog, or the user's own code when custom_bank is set; BANK only. No foreign key:
    -- a custom code is never in the catalog, and a catalog bank made inactive stays on the accounts that use it
    bank_code       TEXT CHECK (bank_code IS NULL OR
                                (length(bank_code) BETWEEN 2 AND 10 AND bank_code NOT GLOB '*[^A-Z0-9]*')),
    custom_bank     INTEGER   NOT NULL DEFAULT 0 CHECK (custom_bank IN (0, 1)),
    display_order   INTEGER   NOT NULL DEFAULT 0,
    is_active       INTEGER   NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CHECK (type = 'BANK' OR (account_suffix IS NULL AND card_suffixes = '' AND bank_code IS NULL AND custom_bank = 0)),
    CHECK (custom_bank = 0 OR bank_code IS NOT NULL),
    CHECK (bank_code IS NULL OR custom_bank = 1 OR color_key IS NULL)
);

CREATE INDEX idx_accounts_user ON accounts (user_id);

CREATE UNIQUE INDEX idx_accounts_one_cash_per_user ON accounts (user_id) WHERE type = 'CASH';
-- Deleting an account clears its digits, so a plain unique index is enough
CREATE UNIQUE INDEX idx_accounts_user_account_suffix ON accounts (user_id, account_suffix) WHERE account_suffix IS NOT NULL;

-- A listed bank takes its name from the catalog, so two accounts at one bank share it; every other name stays unique
CREATE UNIQUE INDEX idx_accounts_user_name ON accounts (user_id, name) WHERE bank_code IS NULL OR custom_bank = 1;
