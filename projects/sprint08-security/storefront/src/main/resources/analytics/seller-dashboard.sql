-- Профиль storefront_reader фиксирует final=1, включая локальные таблицы Distributed-шардов.
-- Только кабинет продавца; область данных задаётся серверным source_id.
-- Один HTTP-запрос: текущие дни/товары и итог предыдущего равного окна, оба диапазона ограничены ключом.
SELECT * FROM
(
    SELECT
        grouping(order_day, sku) AS bucket,
        toString(order_day) AS day,
        sku,
        argMax(title, tuple(created_at, order_uuid)) AS title,
        uniqExact(order_uuid) AS orders,
        sum(quantity) AS units,
        sum(amount) AS order_value
    FROM storefront.paid_purchase_facts
    PREWHERE source_id = {source:UUID} AND order_day >= {start:Date} AND order_day <= {end:Date}
    GROUP BY GROUPING SETS ((), (order_day), (sku))

    UNION ALL

    SELECT
        4 AS bucket,
        '1970-01-01' AS day,
        '' AS sku,
        '' AS title,
        uniqExact(order_uuid) AS orders,
        sum(quantity) AS units,
        sum(amount) AS order_value
    FROM storefront.paid_purchase_facts
    PREWHERE source_id = {source:UUID}
        AND order_day >= {start:Date} - toIntervalDay(dateDiff('day', {start:Date}, {end:Date}) + 1)
        AND order_day < {start:Date}
)
ORDER BY bucket, units DESC, order_value DESC, sku
LIMIT {limit:UInt8} BY bucket
FORMAT JSONEachRow
