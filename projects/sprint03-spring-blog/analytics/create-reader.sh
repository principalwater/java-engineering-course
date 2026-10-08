#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 -v reader_password="$READER_PASSWORD" -v db_name="$PGDATABASE" <<'SQL'
SELECT 'CREATE ROLE blog_reader LOGIN' WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'blog_reader'
) \gexec
ALTER ROLE blog_reader PASSWORD :'reader_password';
GRANT CONNECT ON DATABASE :"db_name" TO blog_reader;
GRANT USAGE ON SCHEMA public TO blog_reader;
GRANT SELECT (id, title, text, likes_count) ON posts TO blog_reader;
GRANT SELECT ON post_tags, comments TO blog_reader;
SQL
