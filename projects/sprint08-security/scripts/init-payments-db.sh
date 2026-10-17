#!/bin/sh
set -eu
password=$(cat /run/payments-secrets/runtime_password)
case "$password" in
  ''|*[!a-f0-9]*) echo 'Payments runtime password must be a nonempty hexadecimal secret' >&2; exit 1 ;;
esac
# Пароль передаётся по stdin: он не попадает в argv, env, Docker metadata или state.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<SQL
CREATE ROLE payments_runtime LOGIN PASSWORD '$password';
\i /setup/postgresql-runtime-grants.sql
SQL
unset password
