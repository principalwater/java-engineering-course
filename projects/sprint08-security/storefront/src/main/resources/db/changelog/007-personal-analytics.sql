-- Новая таблица фактов содержит владельца; повторяем сохранённые снимки один раз без удаления заказов.
CREATE TABLE IF NOT EXISTS analytics_versions (id INTEGER PRIMARY KEY);
UPDATE analytics_outbox SET delivered_at = NULL
WHERE NOT EXISTS (SELECT 1 FROM analytics_versions WHERE id = 1);
INSERT INTO analytics_versions (id) SELECT 1
WHERE NOT EXISTS (SELECT 1 FROM analytics_versions WHERE id = 1);
