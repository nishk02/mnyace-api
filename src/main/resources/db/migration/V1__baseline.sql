-- Baseline: the whole schema as of the migration squash. Later changes are new versioned files; never edit this one.

-- ============================================================
-- categories (with the seeded system categories)
-- ============================================================
CREATE TABLE categories
(
    id            TEXT    NOT NULL PRIMARY KEY,
    name          TEXT    NOT NULL UNIQUE,
    icon          TEXT,
    is_system     INTEGER NOT NULL DEFAULT 1,
    is_active     INTEGER NOT NULL DEFAULT 1,
    is_internal   INTEGER NOT NULL DEFAULT 0,
    display_order INTEGER NOT NULL DEFAULT 0
);

INSERT INTO categories (id, name, icon, is_system, is_active, is_internal, display_order)
VALUES ('cat-01', 'Account', '📊', 1, 1, 0, 1),
       ('cat-02', 'Adjustment', '🔄', 1, 1, 1, 2),
       ('cat-03', 'Allowances', '💰', 1, 1, 0, 3),
       ('cat-04', 'Cashback', '💸', 1, 1, 0, 4),
       ('cat-05', 'CC Payment', '💳', 1, 1, 0, 5),
       ('cat-06', 'Clothing', '👔', 1, 1, 0, 6),
       ('cat-07', 'Donation', '🤝', 1, 1, 0, 7),
       ('cat-08', 'Family & Friends', '👨‍👩‍👧', 1, 1, 0, 8),
       ('cat-09', 'Food & Beverages', '🍔', 1, 1, 0, 9),
       ('cat-10', 'Fuel', '⛽', 1, 1, 0, 10),
       ('cat-11', 'Gifts', '🎁', 1, 1, 0, 11),
       ('cat-12', 'Groceries', '🛒', 1, 1, 0, 12),
       ('cat-13', 'Grooming', '💈', 1, 1, 0, 13),
       ('cat-14', 'Healthcare', '🏥', 1, 1, 0, 14),
       ('cat-15', 'Household', '🏠', 1, 1, 0, 15),
       ('cat-16', 'Insurance', '🛡', 1, 1, 0, 16),
       ('cat-17', 'Investments', '📈', 1, 1, 0, 17),
       ('cat-18', 'Loan', '🏦', 1, 1, 0, 18),
       ('cat-19', 'Miscellaneous', '📦', 1, 1, 0, 19),
       ('cat-20', 'Paycheck', '💵', 1, 1, 0, 20),
       ('cat-21', 'Pets', '🐾', 1, 1, 0, 21),
       ('cat-22', 'Phone', '📱', 1, 1, 0, 22),
       ('cat-23', 'Refreshment', '😋', 1, 1, 0, 23),
       ('cat-24', 'Refund', '↩️', 1, 1, 0, 24),
       ('cat-25', 'Rent', '🏠', 1, 1, 0, 25),
       ('cat-26', 'Restaurant', '🍽', 1, 1, 0, 26),
       ('cat-27', 'Savings', '🐷', 1, 1, 0, 27),
       ('cat-28', 'Subscriptions', '🔔', 1, 1, 0, 28),
       ('cat-29', 'Telephone', '☎️', 1, 1, 0, 29),
       ('cat-30', 'Tips', '🤌', 1, 1, 0, 30),
       ('cat-31', 'Transport', '🚌', 1, 1, 0, 31),
       ('cat-32', 'Utilities', '💡', 1, 1, 0, 32),
       ('cat-33', 'Vehicle', '🚗', 1, 1, 0, 33),
       ('cat-34', 'Wellness', '🧘', 1, 1, 0, 34),
       ('cat-35', 'Opening Balance', '🏛️', 1, 1, 1, 35);

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users
(
    id              TEXT        NOT NULL PRIMARY KEY,
    first_name      TEXT        NOT NULL,
    last_name       TEXT        NOT NULL,
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    role            TEXT        NOT NULL DEFAULT 'MEMBER',
    onboarding_step INTEGER     NOT NULL DEFAULT 0,
    consent_version     TEXT,
    consent_accepted_at TIMESTAMP,
    adult_confirmed     BOOLEAN,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);

CREATE INDEX idx_users_email ON users (email);

-- ============================================================
-- accounts
-- ============================================================
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

-- ============================================================
-- transactions
-- ============================================================
CREATE TABLE transactions
(
    id                  TEXT        NOT NULL PRIMARY KEY,
    user_id             TEXT        NOT NULL REFERENCES users (id),
    date                TEXT        NOT NULL,
    type                TEXT        NOT NULL CHECK (type IN (
                            'FIXED_EXPENSE', 'VARIABLE_EXPENSE', 'INCOME',
                            'LENDING', 'BORROWING', 'REPAYMENT',
                            'SETTLEMENT', 'TRANSFER'
                        )),
    category_id         TEXT        NOT NULL REFERENCES categories (id),
    account_id          TEXT        NOT NULL REFERENCES accounts (id),
    to_account_id       TEXT        REFERENCES accounts (id),
    to_goal_id          TEXT,
    amount              REAL        NOT NULL,
    notes               TEXT,
    financial_year      TEXT        NOT NULL,
    month               INTEGER     NOT NULL,
    calendar_month      INTEGER     NOT NULL,
    calendar_year       INTEGER     NOT NULL,
    is_planned          INTEGER     NOT NULL DEFAULT 0,
    planned_amount_id   TEXT,
    goal_credit_applied REAL,
    import_ref          TEXT,
    merchant            TEXT,
    merchant_key        TEXT,
    source              TEXT        NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'SMS')),
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL,
    CHECK (type != 'TRANSFER' OR to_account_id IS NOT NULL)
);

CREATE INDEX idx_transactions_user_date ON transactions (user_id, date DESC);
CREATE INDEX idx_transactions_user_calendar ON transactions (user_id, calendar_year, calendar_month);
CREATE INDEX idx_transactions_user_fy_month ON transactions (user_id, financial_year, month);
CREATE INDEX idx_transactions_account ON transactions (account_id);
-- A client-generated key for an imported transaction (e.g. from a bank SMS), so reading it twice creates it once
CREATE UNIQUE INDEX idx_transactions_user_import_ref ON transactions (user_id, import_ref) WHERE import_ref IS NOT NULL;

-- The merchant an imported SMS named, and its normalised form, so a category chosen once can be offered again
CREATE INDEX idx_transactions_user_merchant_key ON transactions (user_id, merchant_key) WHERE merchant_key IS NOT NULL;

-- ============================================================
-- goals
-- ============================================================
CREATE TABLE goals
(
    id                          TEXT        NOT NULL PRIMARY KEY,
    user_id                     TEXT        NOT NULL REFERENCES users (id),
    name                        TEXT        NOT NULL,
    target_amount               REAL        NOT NULL CHECK (target_amount > 0),
    account_id                  TEXT        NOT NULL REFERENCES accounts (id),
    start_date                  TEXT        NOT NULL,
    end_date                    TEXT        NOT NULL,
    current_progress            REAL        NOT NULL DEFAULT 0,
    monthly_savings_required    REAL,
    display_order               INTEGER     NOT NULL DEFAULT 0,
    status                      TEXT        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN (
                                    'IN_PROGRESS', 'UPCOMING', 'COMPLETED', 'PAUSED'
                                )),
    is_active                   INTEGER     NOT NULL DEFAULT 1,
    created_at                  TIMESTAMP   NOT NULL,
    updated_at                  TIMESTAMP   NOT NULL,
    UNIQUE (user_id, name)
);

CREATE INDEX idx_goals_user_status ON goals (user_id, status) WHERE is_active = 1;

-- ============================================================
-- invites
-- ============================================================
CREATE TABLE invites (
    id                     TEXT      NOT NULL PRIMARY KEY,
    email                  TEXT      NOT NULL,
    token                  TEXT      NOT NULL UNIQUE,
    status                 TEXT      NOT NULL DEFAULT 'PENDING'
                               CHECK(status IN ('PENDING','AWAITING_OTP','COMPLETED')),
    invited_by             TEXT      NOT NULL REFERENCES users(id),
    expires_at             TIMESTAMP NOT NULL,
    pending_first_name     TEXT,
    pending_last_name      TEXT,
    pending_password_hash  TEXT,
    pending_consent_version TEXT,
    pending_consent_at     TIMESTAMP,
    pending_adult_confirmed BOOLEAN,
    otp_code_hash          TEXT,
    otp_expires_at         TIMESTAMP,
    otp_attempts           INTEGER   NOT NULL DEFAULT 0,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL
);

-- ============================================================
-- transaction goal allocations
-- ============================================================
CREATE TABLE transaction_goal_allocations
(
    id              TEXT        NOT NULL PRIMARY KEY,
    transaction_id  TEXT        NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    goal_id         TEXT        NOT NULL REFERENCES goals (id),
    amount          REAL        NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMP   NOT NULL,
    UNIQUE (transaction_id, goal_id)
);

CREATE INDEX idx_tga_transaction ON transaction_goal_allocations (transaction_id);
CREATE INDEX idx_tga_goal ON transaction_goal_allocations (goal_id);

-- ============================================================
-- refresh tokens
-- ============================================================
CREATE TABLE refresh_tokens
(
    id            TEXT        NOT NULL PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES users (id),
    token_hash    TEXT        NOT NULL UNIQUE,
    family_id     TEXT        NOT NULL,
    device_label  TEXT,
    ip_address    TEXT,
    user_agent    TEXT,
    expires_at    TIMESTAMP   NOT NULL,
    session_started_at TIMESTAMP NOT NULL,
    revoked_at    TIMESTAMP,
    last_active_at  TIMESTAMP   NOT NULL,
    created_at    TIMESTAMP   NOT NULL
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- ============================================================
-- password resets
-- ============================================================
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

-- ============================================================
-- banks (the rows live in R__banks.sql)
-- ============================================================
-- The bank list a user picks from when adding a Bank account. Changes ship as migrations, not endpoints.
CREATE TABLE banks
(
    code          TEXT    NOT NULL PRIMARY KEY CHECK (length(code) BETWEEN 2 AND 10 AND code NOT GLOB '*[^A-Z0-9]*'),
    name          TEXT    NOT NULL,
    display_order INTEGER NOT NULL,
    -- the bank's colour as text on a white page and on a #121212 page, both #RRGGBB
    color_light   TEXT    NOT NULL CHECK (color_light GLOB '#[0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]'),
    color_dark    TEXT    NOT NULL CHECK (color_dark GLOB '#[0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]'),
    is_active     INTEGER NOT NULL DEFAULT 1
);
