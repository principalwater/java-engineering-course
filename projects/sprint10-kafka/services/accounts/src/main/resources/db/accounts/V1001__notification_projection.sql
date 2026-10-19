CREATE TABLE notification_inbox (
    source varchar(16) NOT NULL CHECK(source IN ('ACCOUNTS','CASH','TRANSFER')),
    event_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    payload jsonb NOT NULL,
    read_at timestamptz,
    PRIMARY KEY(source,event_id)
);
CREATE UNIQUE INDEX notification_inbox_owner_sequence ON notification_inbox(owner_id,sequence DESC);
