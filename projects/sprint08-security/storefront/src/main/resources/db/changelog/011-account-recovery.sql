-- Старые аккаунты остаются без почты (NULL) и не восстанавливают доступ, пока контакт не внесён оператором.
ALTER TABLE store_users ADD COLUMN IF NOT EXISTS email VARCHAR(254);
-- Эпоха учётных данных: смена пароля увеличивает её, и все прежние сессии перестают быть действительными.
ALTER TABLE store_users ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS store_users_email_idx ON store_users(email);
-- Одна строка на аккаунт: PRIMARY KEY даёт «один действующий токен» и атомарную паузу между запросами.
CREATE TABLE IF NOT EXISTS password_resets (
    user_id UUID PRIMARY KEY REFERENCES store_users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    requested_at TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(3) WITH TIME ZONE NOT NULL
);
