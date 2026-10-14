-- Revision и каталог фиксируются одной SQL-транзакцией; Redis не хранит источник истины.
ALTER TABLE store_identity ADD COLUMN IF NOT EXISTS catalog_revision BIGINT NOT NULL DEFAULT 0;
