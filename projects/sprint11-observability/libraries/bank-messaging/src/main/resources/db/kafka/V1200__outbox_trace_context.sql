-- Родитель трассы (W3C traceparent/tracestate) сохраняется вместе с бизнес-фактом: отложенная
-- публикация продолжает исходную трассу. Колонки допускают NULL: старые записи и запуски без
-- трассировки публикуются как раньше, идентичность и аренда записи не меняются.
ALTER TABLE bank_kafka_outbox ADD COLUMN traceparent varchar(55), ADD COLUMN tracestate varchar(512);
