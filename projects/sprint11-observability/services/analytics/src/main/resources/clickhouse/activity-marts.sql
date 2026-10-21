-- Применяется администратором после основной events DDL; прикладной writer не получает DDL.
ALTER TABLE bank_analytics.events MODIFY COLUMN event_type Enum8(
    'DEPOSIT' = 1, 'WITHDRAW' = 2, 'TRANSFER_IN' = 3, 'TRANSFER_OUT' = 4, 'CABINET_VIEW' = 5,
    'PROFILE_UPDATED' = 6, 'NOTIFICATION_AVAILABLE' = 7, 'NOTIFICATION_READ' = 8);

CREATE TABLE IF NOT EXISTS bank_analytics.activity_snapshots
(
    owner_id UUID,
    event_date Date,
    source LowCardinality(String),
    event_type LowCardinality(String),
    section LowCardinality(String),
    events_count UInt64,
    revision UInt64
)
ENGINE = ReplacingMergeTree(revision)
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, owner_id, source, event_type, section)
TTL event_date + INTERVAL 7 DAY TO VOLUME 'cold', event_date + INTERVAL 180 DAY DELETE
SETTINGS storage_policy = 'bank_tiered';

-- Явный DEFINER даёт BI только агрегаты без SELECT на исходные owner rows.
-- 30 UTC-дней сохраняют тренд на границе месяцев; только три резервных UUID относятся к demo.
CREATE OR REPLACE VIEW bank_analytics.financial_daily
DEFINER = bank_writer SQL SECURITY DEFINER
AS SELECT event_date AS date,
    if(owner_id IN (toUUID('de110000-0000-4000-8000-000000000001'),
        toUUID('de110000-0000-4000-8000-000000000002'),
        toUUID('de110000-0000-4000-8000-000000000003')), 'demo', 'live') AS data_origin,
    sumIf(amount_minor, event_type = 'DEPOSIT') AS incoming_minor,
    -sumIf(amount_minor, event_type = 'WITHDRAW') AS outgoing_minor,
    -sumIf(amount_minor, event_type = 'TRANSFER_OUT') AS transfer_minor,
    countIf(event_type IN ('DEPOSIT','WITHDRAW','TRANSFER_OUT')) AS operations_count,
    uniqExact(owner_id) AS accounts_count
FROM bank_analytics.events FINAL
WHERE event_date >= subtractDays(toDate(now('UTC')), 29) AND event_date <= toDate(now('UTC'))
    AND event_type IN ('DEPOSIT','WITHDRAW','TRANSFER_OUT')
GROUP BY event_date, data_origin;

CREATE OR REPLACE VIEW bank_analytics.engagement_daily
DEFINER = bank_writer SQL SECURITY DEFINER
AS SELECT event_date AS date,
    if(owner_id IN (toUUID('de110000-0000-4000-8000-000000000001'),
        toUUID('de110000-0000-4000-8000-000000000002'),
        toUUID('de110000-0000-4000-8000-000000000003')), 'demo', 'live') AS data_origin,
    source, event_type, section, sum(events_count) AS events_count
FROM bank_analytics.activity_snapshots FINAL
WHERE event_date >= subtractDays(toDate(now('UTC')), 29) AND event_date <= toDate(now('UTC'))
    AND event_type IN ('CABINET_VIEW','PROFILE_UPDATED','SCREEN_OPENED','FORM_OPENED')
GROUP BY event_date, data_origin, source, event_type, section;

CREATE OR REPLACE VIEW bank_analytics.notification_daily
DEFINER = bank_writer SQL SECURITY DEFINER
AS SELECT event_date AS date,
    if(owner_id IN (toUUID('de110000-0000-4000-8000-000000000001'),
        toUUID('de110000-0000-4000-8000-000000000002'),
        toUUID('de110000-0000-4000-8000-000000000003')), 'demo', 'live') AS data_origin,
    sumIf(events_count, event_type = 'NOTIFICATION_AVAILABLE') AS available_count,
    sumIf(events_count, event_type = 'NOTIFICATION_READ') AS read_count
FROM bank_analytics.activity_snapshots FINAL
WHERE event_date >= subtractDays(toDate(now('UTC')), 29) AND event_date <= toDate(now('UTC'))
    AND event_type IN ('NOTIFICATION_AVAILABLE','NOTIFICATION_READ')
GROUP BY event_date, data_origin;
