-- Монотонный курсор позволяет оператору переоткрывать историю конечными неизменяемыми страницами.
ALTER TABLE bank_kafka_outbox ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX bank_kafka_outbox_sequence ON bank_kafka_outbox(sequence);
