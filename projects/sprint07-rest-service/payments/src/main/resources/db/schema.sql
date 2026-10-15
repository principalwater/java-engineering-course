CREATE TABLE IF NOT EXISTS payment_wallets (
    account_id UUID PRIMARY KEY,
    opening_balance_minor BIGINT NOT NULL CHECK (opening_balance_minor BETWEEN 1 AND 1000000000000000),
    balance_minor BIGINT NOT NULL CHECK (balance_minor BETWEEN 0 AND 1000000000000000),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'RUB'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_id, opening_balance_minor)
);
CREATE TABLE IF NOT EXISTS payment_decisions (
    payment_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES payment_wallets(account_id),
    reference_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000000),
    balance_minor BIGINT NOT NULL CHECK (balance_minor BETWEEN 0 AND 1000000000000000),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'RUB'),
    status VARCHAR(9) NOT NULL CHECK (status IN ('SUCCEEDED', 'DECLINED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_id, reference_id),
    UNIQUE(payment_id, account_id, amount_minor, status)
);
-- Две стороны одной записи проверяются самой SQL-схемой: половины проводки не существует даже при ошибке приложения.
CREATE TABLE IF NOT EXISTS payment_journal (
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('OPENING', 'DEBIT')),
    journal_id UUID NOT NULL,
    payment_id UUID UNIQUE,
    payment_status VARCHAR(9),
    opening_minor BIGINT,
    account_id UUID NOT NULL REFERENCES payment_wallets(account_id),
    positive_account VARCHAR(8) NOT NULL,
    negative_account VARCHAR(8) NOT NULL,
    positive_minor BIGINT NOT NULL CHECK (positive_minor BETWEEN 1 AND 1000000000000000),
    negative_minor BIGINT NOT NULL CHECK (negative_minor BETWEEN -1000000000000000 AND -1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(kind, journal_id),
    FOREIGN KEY(payment_id, account_id, positive_minor, payment_status)
        REFERENCES payment_decisions(payment_id, account_id, amount_minor, status),
    FOREIGN KEY(account_id, opening_minor) REFERENCES payment_wallets(account_id, opening_balance_minor),
    CHECK (positive_account <> negative_account),
    CHECK (positive_minor + negative_minor = 0),
    CHECK ((kind = 'OPENING' AND journal_id = account_id AND payment_id IS NULL AND payment_status IS NULL
            AND opening_minor IS NOT NULL AND opening_minor = positive_minor
            AND positive_account = 'CUSTOMER' AND negative_account = 'ISSUER')
        OR (kind = 'DEBIT' AND journal_id = payment_id AND payment_id IS NOT NULL AND payment_status IS NOT NULL
            AND payment_status = 'SUCCEEDED' AND opening_minor IS NULL
            AND positive_account = 'CLEARING' AND negative_account = 'CUSTOMER'))
);
CREATE OR REPLACE VIEW payment_journal_entries AS
    SELECT kind, journal_id, account_id, positive_account AS ledger_account, positive_minor AS amount_minor FROM payment_journal
    UNION ALL
    SELECT kind, journal_id, account_id, negative_account AS ledger_account, negative_minor AS amount_minor FROM payment_journal;
