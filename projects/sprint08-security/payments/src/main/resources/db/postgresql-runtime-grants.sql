-- В production применяет владелец схемы после её создания; роль payments_runtime и её секрет создаются снаружи.
-- Запускать приложение этой ролью с PAYMENTS_SQL_INIT_MODE=never: DDL остаётся у отдельного владельца.
REVOKE ALL ON payment_wallets, payment_decisions, payment_journal, payment_journal_entries FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payments_runtime;
GRANT SELECT, INSERT ON payment_wallets, payment_decisions, payment_journal TO payments_runtime;
GRANT UPDATE(balance_minor) ON payment_wallets TO payments_runtime;
GRANT SELECT ON payment_journal_entries TO payments_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON payment_decisions, payment_journal FROM payments_runtime;
