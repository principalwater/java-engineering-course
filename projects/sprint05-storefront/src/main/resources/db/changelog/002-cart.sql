CREATE SEQUENCE cart_line_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE shopping_carts (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL
);
CREATE TABLE cart_items (
    id BIGINT PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES shopping_carts(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    UNIQUE (cart_id, product_id)
);
