CREATE DATABASE IF NOT EXISTS bank_analytics;

CREATE TABLE IF NOT EXISTS bank_analytics.events
(
    event_id UUID,
    owner_id UUID,
    event_type Enum8('DEPOSIT' = 1, 'WITHDRAW' = 2, 'TRANSFER_IN' = 3, 'TRANSFER_OUT' = 4, 'CABINET_VIEW' = 5),
    amount_minor Int64,
    occurred_at DateTime64(3, 'UTC'),
    event_date Date MATERIALIZED toDate(occurred_at),
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, owner_id, event_id)
TTL toDateTime(occurred_at) + INTERVAL 7 DAY TO VOLUME 'cold',
    toDateTime(occurred_at) + INTERVAL 180 DAY DELETE
SETTINGS storage_policy = 'bank_tiered';
