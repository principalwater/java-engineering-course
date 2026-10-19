-- Старые receipts проходят тот же Kafka-канал, что и новые; исходные строки сохраняются.
INSERT INTO bank_kafka_outbox(id,topic,message_key,message_id,payload)
SELECT gen_random_uuid(),'bank.notifications.receipts.v1',owner_id::text,
    CASE caller WHEN 'accounts-service' THEN 'ACCOUNTS' WHEN 'cash-service' THEN 'CASH' ELSE 'TRANSFER' END || ':' || event_id,
    jsonb_build_object('source',CASE caller WHEN 'accounts-service' THEN 'ACCOUNTS' WHEN 'cash-service' THEN 'CASH' ELSE 'TRANSFER' END,'event',payload)
FROM bank_notification WHERE caller IN ('accounts-service','cash-service','transfer-service')
ON CONFLICT(topic,message_id) DO NOTHING;
