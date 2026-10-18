#!/bin/sh
set -eu
# Пароли передаются psql по stdin, а не в аргументах, окружении или Docker metadata.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
REVOKE ALL ON DATABASE bank FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
for name in accounts cash transfer notifications analytics keycloak; do
    password=$(cat "/run/bank-secrets/$name")
    case "$password" in
        ''|*[!a-f0-9]*) echo 'Database role password must be a nonempty hexadecimal secret' >&2; exit 1 ;;
    esac
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<SQL
CREATE ROLE $name LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$password';
GRANT CONNECT ON DATABASE bank TO $name;
CREATE SCHEMA $name AUTHORIZATION $name;
ALTER ROLE $name IN DATABASE bank SET search_path TO $name;
ALTER ROLE $name SET statement_timeout = '10s';
ALTER ROLE $name SET lock_timeout = '3s';
ALTER ROLE $name SET idle_in_transaction_session_timeout = '10s';
SQL
    unset password
done
