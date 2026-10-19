CREATE TABLE bank_intent (
    id uuid PRIMARY KEY,
    issuer varchar(512) NOT NULL,
    subject varchar(128) NOT NULL,
    operation_type varchar(16) NOT NULL CHECK (operation_type IN ('DEPOSIT', 'WITHDRAW', 'TRANSFER')),
    request_id uuid NOT NULL,
    payload jsonb NOT NULL,
    result jsonb,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (issuer, subject, operation_type, request_id)
);
CREATE INDEX bank_intent_due ON bank_intent(next_attempt_at, id) WHERE result IS NULL;
