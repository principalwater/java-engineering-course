#!/bin/bash
set -euo pipefail
umask 077
admin="$(cat /run/secrets/cassandra_admin_password)"
activity="$(cat /run/secrets/cassandra_activity_password)"
if [[ ! "$admin" =~ ^[a-f0-9]{64}$ || ! "$activity" =~ ^[a-f0-9]{64}$ ]]; then
    echo 'Cassandra requires 64-character hexadecimal secret files' >&2
    exit 1
fi
configure_client() {
    printf '[auth_provider]\nmodule = cassandra.auth\nclassname = PlainTextAuthProvider\n' > /tmp/cqlshrc
    printf '[PlainTextAuthProvider]\nusername = cassandra\npassword = %s\n' "$1" > /tmp/cql-credentials
}
configure_client "$admin"
for attempt in {1..60}; do
    if cqlsh cassandra 9042 --cqlshrc=/tmp/cqlshrc --credentials=/tmp/cql-credentials --connect-timeout=3 --request-timeout=5 -e 'SELECT release_version FROM system.local' >/dev/null 2>&1; then
        break
    fi
    # Начальный upstream-пароль используется только до первого успешного bootstrap.
    configure_client cassandra
    if cqlsh cassandra 9042 --cqlshrc=/tmp/cqlshrc --credentials=/tmp/cql-credentials --connect-timeout=3 --request-timeout=5 -e 'SELECT release_version FROM system.local' >/dev/null 2>&1; then
        break
    fi
    configure_client "$admin"
    if [ "$attempt" = 60 ]; then
        echo 'Cassandra authentication initialization timed out' >&2
        exit 1
    fi
    sleep 2
done
# CQL с паролями не попадает в argv, env, журналы или постоянный volume.
# Пароль задаётся один раз: Cassandra 5 запрещает немедленный повтор после CREATE.
cat > /tmp/bootstrap.cql <<CQL
CREATE KEYSPACE IF NOT EXISTS bank_activity WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 1};
CREATE ROLE IF NOT EXISTS bank_activity;
ALTER ROLE bank_activity WITH PASSWORD = '$activity' AND LOGIN = true AND SUPERUSER = false;
GRANT ALL PERMISSIONS ON KEYSPACE bank_activity TO bank_activity;
ALTER ROLE cassandra WITH PASSWORD = '$admin';
CQL
if ! result="$(cqlsh cassandra 9042 --cqlshrc=/tmp/cqlshrc --credentials=/tmp/cql-credentials --connect-timeout=3 --request-timeout=15 -f /tmp/bootstrap.cql 2>&1)"; then
    result="${result//"$admin"/[redacted]}"
    result="${result//"$activity"/[redacted]}"
    printf 'Cassandra role initialization failed: %s\n' "$result" >&2
    exit 1
fi
rm -f /tmp/bootstrap.cql /tmp/cqlshrc /tmp/cql-credentials
unset admin activity
echo 'Cassandra bank keyspace and private roles are ready'
