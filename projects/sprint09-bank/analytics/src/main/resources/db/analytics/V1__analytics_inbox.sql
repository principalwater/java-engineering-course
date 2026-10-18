CREATE TABLE analytics_inbox (
    event_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    event_type varchar(40) NOT NULL CHECK (event_type IN
        ('DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT', 'CABINET_VIEW')),
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    accepted_at timestamptz NOT NULL,
    cassandra_done boolean NOT NULL DEFAULT false,
    warehouse_done boolean NOT NULL DEFAULT false,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_token uuid,
    lease_until timestamptz
);
CREATE INDEX analytics_inbox_due ON analytics_inbox(next_attempt_at, event_id)
    WHERE NOT (cassandra_done AND warehouse_done);
CREATE INDEX analytics_inbox_owner_month ON analytics_inbox(owner_id, occurred_at);
CREATE INDEX analytics_inbox_month ON analytics_inbox(occurred_at);
