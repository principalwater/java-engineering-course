-- Порядок partial index совпадает с scan: свежие факты не стоят за due-повторами старого outage.
DROP INDEX analytics_inbox_due;
CREATE INDEX analytics_inbox_due ON analytics_inbox((attempts > 0), next_attempt_at, event_id)
    WHERE NOT (cassandra_done AND warehouse_done);
