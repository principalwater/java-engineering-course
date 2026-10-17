CREATE TABLE IF NOT EXISTS store_users (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL UNIQUE REFERENCES shopping_carts(id),
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL
);
