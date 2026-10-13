CREATE DATABASE IF NOT EXISTS storefront;

-- Повторная доставка заменяет тот же неизменяемый снимок; FINAL убирает повторы до фонового merge.
CREATE TABLE IF NOT EXISTS storefront.purchase_facts_local
(
    source_id UUID,
    user_id UUID,
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
ENGINE = ReplicatedReplacingMergeTree('/storefront/tables/{shard}/purchase_facts', '{replica}')
ORDER BY (source_id, user_id, order_day, order_uuid, line_number);

-- Шард определяется владельцем: число/веса шардов меняются только с управляемым переносом фактов.
CREATE TABLE IF NOT EXISTS storefront.purchase_facts AS storefront.purchase_facts_local
ENGINE = Distributed(storefront_cluster, storefront, purchase_facts_local, cityHash64(user_id));
