#!/usr/bin/env bash
set -euo pipefail
# Файлы остаются снаружи образа; production использует внешний secret store и TLS.
KC_DB_PASSWORD="$(cat /run/secrets/authorization_db_password)"
KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat /run/secrets/authorization_admin_password)"
STOREFRONT_CLIENT_SECRET="$(cat /run/secrets/oauth_client_secret)"
: "${KC_DB_PASSWORD:?Authorization database password file is empty}"
: "${KC_BOOTSTRAP_ADMIN_PASSWORD:?Authorization admin password file is empty}"
: "${STOREFRONT_CLIENT_SECRET:?OAuth client secret file is empty}"
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD STOREFRONT_CLIENT_SECRET
exec /opt/keycloak/bin/kc.sh start --optimized --import-realm
