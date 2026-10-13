CREATE DATABASE IF NOT EXISTS storefront;

-- Повторная доставка заменяет тот же неизменяемый снимок; FINAL убирает повторы до фонового merge.
CREATE TABLE IF NOT EXISTS storefront.order_lines
(
    source_id UUID,
    order_uuid UUID,
    order_id UInt64,
    created_at DateTime64(3, 'UTC'),
    order_day Date MATERIALIZED toDate(created_at),
    line_number UInt8,
    product_id UInt64,
    sku LowCardinality(String),
    title String,
    price UInt64,
    quantity UInt8,
    amount UInt64 MATERIALIZED price * toUInt64(quantity),
    CONSTRAINT positive_price CHECK price > 0,
    CONSTRAINT positive_quantity CHECK quantity > 0
)
ENGINE = ReplacingMergeTree
ORDER BY (source_id, order_day, order_uuid, line_number);
