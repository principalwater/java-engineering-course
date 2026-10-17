#!/bin/sh
set -eu
# Штатный initdb меняет UID: postgres получает отдельную private-копию host-owned файла 0600.
mkdir -p /run/payments-secrets
chmod 0700 /run/payments-secrets
cp /run/secrets/payments_runtime_password /run/payments-secrets/runtime_password
chown -R postgres:postgres /run/payments-secrets
chmod 0600 /run/payments-secrets/runtime_password
exec docker-entrypoint.sh "$@"
