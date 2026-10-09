-- PostgreSQL остаётся источником истины; агрегаты считаются в ClickHouse без копирования картинок.
SELECT p.id, p.title, p.likes_count, coalesce(c.comments_count, 0) AS comments_count
FROM (
    SELECT id, title, likes_count
    FROM postgresql(blog_pg, table='posts')
    WHERE id > 0
) AS p
LEFT ANY JOIN (
    SELECT post_id, count() AS comments_count
    FROM postgresql(blog_pg, table='comments')
    WHERE post_id > 0
    GROUP BY post_id
) AS c ON p.id = c.post_id
ORDER BY p.likes_count DESC, comments_count DESC, p.id DESC
LIMIT 20;
