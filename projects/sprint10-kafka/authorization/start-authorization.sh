#!/usr/bin/env bash
set -euo pipefail
umask 077
read_secret() {
    local value
    value="$(cat "/run/secrets/$1")"
    if [[ ! "$value" =~ ^[a-f0-9]{64}$ ]]; then
        echo 'Authorization requires a 64-character hexadecimal secret file' >&2
        exit 1
    fi
    printf '%s' "$value"
}
# Конфигурация и импорт существуют только в private tmpfs, без секретов в argv/env.
{
    printf 'db-password=%s\n' "$(read_secret db_keycloak_password)"
    printf 'bootstrap-admin-password=%s\n' "$(read_secret authorization_admin_password)"
} > /tmp/keycloak.conf
realm="$(cat /setup/bank-realm.json)"
for name in oauth_front_secret oauth_accounts_secret oauth_cash_secret oauth_transfer_secret oauth_analytics_secret demo_alice_password demo_bob_password demo_analyst_password; do
    value="$(read_secret "$name")"
    placeholder="__${name}__"
    realm="${realm//"$placeholder"/"$value"}"
done
mkdir -p /opt/keycloak/data/import
printf '%s\n' "$realm" > /opt/keycloak/data/import/bank-realm.json
unset realm value placeholder
exec /opt/keycloak/bin/kc.sh --config-file=/tmp/keycloak.conf start --optimized --import-realm
