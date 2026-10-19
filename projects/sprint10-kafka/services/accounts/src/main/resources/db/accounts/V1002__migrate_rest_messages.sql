-- Неизвестный старый маршрут требует решения оператора, а не молчаливой смены назначения.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM bank_outbox WHERE status='PENDING' AND NOT ((target='notifications' AND path='/internal/notifications') OR (target='analytics' AND path='/internal/analytics/events'))) THEN
        RAISE EXCEPTION 'Unsupported pending outbox route';
    END IF;
END $$;
-- HTTP больше не обслуживает эти направления; сохранённый payload переходит в Kafka без нового ID.
ALTER TABLE bank_outbox DROP CONSTRAINT bank_outbox_status_check;
ALTER TABLE bank_outbox ADD CONSTRAINT bank_outbox_status_check CHECK(status IN ('PENDING','DELIVERED','REJECTED','MIGRATED'));
INSERT INTO bank_kafka_outbox(id,topic,message_key,message_id,payload)
SELECT id,CASE target WHEN 'notifications' THEN 'bank.accounts.notifications.v1' WHEN 'analytics' THEN 'bank.accounts.analytics.v1' END,payload->>'ownerId',event_id::text,payload
FROM bank_outbox WHERE status='PENDING' AND ((target='notifications' AND path='/internal/notifications') OR (target='analytics' AND path='/internal/analytics/events'))
ON CONFLICT(topic,message_id) DO NOTHING;
UPDATE bank_outbox SET status='MIGRATED',lease_token=NULL,lease_until=NULL
WHERE status='PENDING' AND ((target='notifications' AND path='/internal/notifications') OR (target='analytics' AND path='/internal/analytics/events'));
