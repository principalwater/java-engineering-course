#!/bin/sh
set -eu
umask 077
# Штатный entrypoint меняет UID: private tmpfs-копии доступны только postgres.
mkdir -p /run/bank-secrets
for name in accounts cash transfer notifications analytics keycloak; do
    cp "/run/secrets/db_${name}_password" "/run/bank-secrets/$name"
done
chown -R postgres:postgres /run/bank-secrets
chmod 0700 /run/bank-secrets
exec docker-entrypoint.sh "$@"
