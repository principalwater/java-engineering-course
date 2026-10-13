CREATE SEQUENCE outbox_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE analytics_outbox (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES purchase_orders(id) ON DELETE CASCADE,
    delivered_at TIMESTAMP(3) WITH TIME ZONE
);
CREATE INDEX outbox_pending_idx ON analytics_outbox(delivered_at, id);
-- UUID отделяет наборы данных: PostgreSQL сохраняет его, новая in-memory H2 получает другой.
CREATE TABLE store_identity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    source_id UUID NOT NULL
);
