#!/bin/bash
set -euo pipefail
umask 077
users="$(cat /setup/clickhouse-users.xml)"
for role in admin writer reader bi; do
    password="$(cat "/run/secrets/analytics_${role}_password")"
    if [[ ! "$password" =~ ^[a-f0-9]{64}$ ]]; then
        echo 'ClickHouse requires 64-character hexadecimal secret files' >&2
        exit 1
    fi
    hash="$(printf '%s' "$password" | sha256sum)"
    hash="${hash%% *}"
    placeholder="__${role}_password_hash__"
    users="${users//"$placeholder"/"$hash"}"
done
mkdir -p /etc/clickhouse-server/users.d
printf '%s\n' "$users" > /etc/clickhouse-server/users.d/bank.xml
chown clickhouse:clickhouse /etc/clickhouse-server/users.d/bank.xml
unset users password hash placeholder
exec /entrypoint.sh "$@"
