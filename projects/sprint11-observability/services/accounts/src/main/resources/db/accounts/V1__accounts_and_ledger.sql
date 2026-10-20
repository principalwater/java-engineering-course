CREATE TABLE account (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    login VARCHAR(128) NOT NULL UNIQUE,
    full_name VARCHAR(160) NOT NULL DEFAULT '',
    birth_date DATE,
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor BETWEEN 0 AND 9000000000000000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (issuer, subject)
);

CREATE TABLE money_operation (
    owner_id UUID NOT NULL REFERENCES account(id),
    type VARCHAR(16) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAW', 'TRANSFER')),
    request_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    recipient_id UUID,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'REJECTED')),
    error VARCHAR(40),
    balance_minor BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, type, request_id)
);

CREATE TABLE ledger_entry (
    sequence BIGSERIAL PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES account(id),
    command_owner_id UUID NOT NULL REFERENCES account(id),
    operation_type VARCHAR(16) NOT NULL,
    request_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor <> 0),
    balance_minor BIGINT NOT NULL CHECK (balance_minor BETWEEN 0 AND 9000000000000000),
    counterparty_id UUID REFERENCES account(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_id, command_owner_id, operation_type, request_id),
    FOREIGN KEY (command_owner_id, operation_type, request_id) REFERENCES money_operation(owner_id, type, request_id)
);
CREATE INDEX ledger_owner_sequence ON ledger_entry (owner_id, sequence DESC);
