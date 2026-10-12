-- Один запрос получает общие показатели, дни и товары из одного чтения фактов.
SELECT
    grouping(order_day, sku) AS bucket,
    toString(order_day) AS day,
    sku,
    argMax(title, tuple(created_at, order_uuid)) AS title,
    uniqExact(order_uuid) AS orders,
    sum(quantity) AS units,
    sum(amount) AS order_value
FROM storefront.order_lines FINAL
PREWHERE source_id = {source:UUID} AND order_day >= {start:Date} AND order_day <= {end:Date}
GROUP BY GROUPING SETS ((), (order_day), (sku))
ORDER BY bucket, units DESC, order_value DESC, sku
LIMIT {limit:UInt8} BY bucket
FORMAT JSONEachRow
