CREATE TABLE bank_kafka_outbox (
    id uuid PRIMARY KEY,
    topic varchar(120) NOT NULL,
    message_key varchar(128) NOT NULL,
    message_id varchar(256) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PUBLISHED')),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(topic,message_id)
);
CREATE INDEX bank_kafka_outbox_due ON bank_kafka_outbox(next_attempt_at,id) WHERE status='PENDING';
