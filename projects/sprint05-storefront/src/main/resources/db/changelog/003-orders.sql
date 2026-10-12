CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE order_line_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE purchase_orders (
    id BIGINT PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    cart_id UUID NOT NULL REFERENCES shopping_carts(id),
    created_at TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    total_sum BIGINT NOT NULL CHECK (total_sum > 0)
);
CREATE INDEX orders_cart_time_idx ON purchase_orders(cart_id, created_at DESC, id DESC);
CREATE TABLE order_items (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    line_number INTEGER NOT NULL CHECK (line_number >= 0),
    product_id BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    image VARCHAR(32) NOT NULL,
    price BIGINT NOT NULL CHECK (price BETWEEN 1 AND 1000000),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    UNIQUE (order_id, line_number)
);
