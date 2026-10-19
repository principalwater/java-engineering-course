CREATE TABLE analytics_quarantine (
    projection varchar(16) NOT NULL CHECK (projection IN ('PERSONAL', 'BUSINESS')),
    topic varchar(100) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    payload_hash varchar(64) NOT NULL,
    reason varchar(40) NOT NULL,
    rejected_at timestamptz NOT NULL,
    PRIMARY KEY (projection, topic, partition_id, record_offset, payload_hash)
);
