#!/bin/bash
# Пароль записи в Elasticsearch читается из смонтированного Secret в ${LOGSTASH_WRITER_PASSWORD},
# которую использует bank-logs.conf; затем управление передаётся штатному entrypoint образа.
set -euo pipefail
umask 077
file=/run/secrets/logstash_writer_password
if [[ ! -s "$file" ]]; then
    echo "Secret file logstash_writer_password is missing or empty" >&2
    exit 1
fi
LOGSTASH_WRITER_PASSWORD="$(tr -d '\r\n' < "$file")"
export LOGSTASH_WRITER_PASSWORD
exec /usr/local/bin/docker-entrypoint "$@"
