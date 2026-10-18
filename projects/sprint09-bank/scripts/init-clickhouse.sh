#!/bin/bash
set -euo pipefail
umask 077
password="$(cat /run/secrets/analytics_admin_password)"
if [[ ! "$password" =~ ^[a-f0-9]{64}$ ]]; then
    echo 'ClickHouse requires a 64-character hexadecimal admin secret file' >&2
    exit 1
fi
printf '<config><user>bank_admin</user><password>%s</password><host>clickhouse</host><connect_timeout>3</connect_timeout><receive_timeout>10</receive_timeout></config>\n' "$password" > /tmp/client.xml
unset password
for attempt in {1..60}; do
    if clickhouse-client --config-file=/tmp/client.xml --query 'SELECT 1' >/dev/null 2>&1; then
        clickhouse-client --config-file=/tmp/client.xml --multiquery < /setup/init-clickhouse.sql
        rm -f /tmp/client.xml
        echo 'ClickHouse bank database is ready'
        exit 0
    fi
    sleep 1
done
echo 'ClickHouse initialization timed out' >&2
exit 1
