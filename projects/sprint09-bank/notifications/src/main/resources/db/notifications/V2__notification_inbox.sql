-- Сохранённые receipts сразу доступны в кабинете; повторная доставка не создаёт новую строку.
ALTER TABLE bank_notification
    ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN owner_id uuid GENERATED ALWAYS AS ((payload->>'ownerId')::uuid) STORED NOT NULL;
-- Keyset по порядку приёма не зависит от одинакового времени событий или новых доставок.
CREATE UNIQUE INDEX notification_owner_sequence ON bank_notification(owner_id, sequence DESC);
