-- Повтор после потери HTTP-ответа остаётся в трассе первой команды; старые intents допустимы без неё.
ALTER TABLE bank_intent ADD COLUMN traceparent varchar(55), ADD COLUMN tracestate varchar(512);
