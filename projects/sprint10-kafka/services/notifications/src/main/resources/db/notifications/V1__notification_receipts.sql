CREATE TABLE bank_notification (
    caller varchar(64) NOT NULL,
    event_id uuid NOT NULL,
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (caller, event_id)
);
