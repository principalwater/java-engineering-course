#!/bin/bash
# Reads the Elasticsearch writer credential from its mounted Secret file into the process environment
# that bank-logs.conf references as ${LOGSTASH_WRITER_PASSWORD}, then hands over to the image entrypoint.
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
