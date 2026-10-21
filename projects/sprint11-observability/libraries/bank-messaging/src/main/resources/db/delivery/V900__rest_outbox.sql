CREATE TABLE bank_outbox (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    target varchar(40) NOT NULL,
    path varchar(256) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'REJECTED')),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target, event_id)
);
CREATE INDEX bank_outbox_due ON bank_outbox(next_attempt_at, id) WHERE status = 'PENDING';
