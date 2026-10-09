#!/bin/sh
set -eu
PGPASSWORD=$(cat "${PGPASSWORD_FILE:?}")
READER_PASSWORD=$(cat "${READER_PASSWORD_FILE:?}")
: "${PGPASSWORD:?Файл пароля PostgreSQL пуст}"
: "${READER_PASSWORD:?Файл пароля роли чтения пуст}"
export PGPASSWORD READER_PASSWORD
# psql получает пароль через окружение, а не через аргументы процесса.
psql -v ON_ERROR_STOP=1 <<'SQL'
\getenv reader_password READER_PASSWORD
\getenv db_name PGDATABASE
SELECT 'CREATE ROLE blog_reader LOGIN' WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'blog_reader'
) \gexec
ALTER ROLE blog_reader PASSWORD :'reader_password';
GRANT CONNECT ON DATABASE :"db_name" TO blog_reader;
GRANT USAGE ON SCHEMA public TO blog_reader;
GRANT SELECT (id, title, text, likes_count) ON posts TO blog_reader;
GRANT SELECT ON post_tags, comments TO blog_reader;
SQL
