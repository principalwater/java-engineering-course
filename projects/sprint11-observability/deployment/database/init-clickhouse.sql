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

-- Диагностика отказов из Java/Kafka отделена от ledger; повтор event_id не удваивает итог.
-- Основные фильтры: день и тип отказа. Месяц задаёт срок хранения, а не уникальную партицию клиента.
CREATE TABLE IF NOT EXISTS bank_analytics.business_failures
(
    event_id UUID,
    owner_id UUID,
    counterparty_id Nullable(UUID),
    failure_type LowCardinality(String),
    source_service LowCardinality(String),
    reason LowCardinality(String),
    currency LowCardinality(String),
    requested_amount_minor UInt64,
    occurred_at DateTime64(3, 'UTC'),
    event_date Date MATERIALIZED toDate(occurred_at),
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, failure_type, owner_id, event_id)
TTL toDateTime(occurred_at) + INTERVAL 7 DAY TO VOLUME 'cold',
    toDateTime(occurred_at) + INTERVAL 180 DAY DELETE
SETTINGS storage_policy = 'bank_tiered';
