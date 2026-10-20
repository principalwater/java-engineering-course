#!/bin/bash
# Idempotent Grafana bootstrap, run as a finite Job: ensures the read-only operator user exists with the
# Viewer role, read access to the Bank11 folder and the password from its Secret file. Datasources and
# dashboards are file-provisioned. The administrator credential is read from its Secret file and never printed.
set -euo pipefail
umask 077

grafana=http://grafana:3000
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    echo "FAIL $*" >&2
    exit 1
}

read_secret() {
    [[ -s "/run/secrets/$1" ]] || fail "Secret file $1 is missing or empty"
    local value
    value="$(tr -d '\r\n' < "/run/secrets/$1")"
    # Values are placed into JSON bodies below, so anything needing an escape is rejected.
    [[ "$value" =~ ^[A-Za-z0-9._~+=-]{16,}$ ]] || fail "Secret file $1 must hold at least 16 characters from A-Za-z0-9._~+=-"
    printf '%s' "$value"
}

printf 'user = "bank11-admin:%s"\n' "$(read_secret grafana_admin_password)" > "$work/auth.conf"
operator_password="$(read_secret grafana_operator_password)"

# call METHOD PATH [BODY]: prints the HTTP status and leaves the response in $work/response. The body goes
# through stdin so that passwords never appear in the curl arguments.
call() {
    local method=$1 path=$2 body=${3:-}
    local -a data=()
    [[ -z "$body" ]] || data=(-H 'Content-Type: application/json' --data-binary @-)
    printf '%s' "$body" | curl -s -m 20 -K "$work/auth.conf" -o "$work/response" -w '%{http_code}' -X "$method" ${data[@]+"${data[@]}"} "$grafana$path" || true
}

expect() {
    local status=$1 allowed=$2 what=$3
    [[ " $allowed " == *" $status "* ]] || fail "$what returned HTTP ${status:-none}"
}

echo "Waiting for Grafana to answer /api/health"
for attempt in $(seq 1 60); do
    [[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$grafana/api/health" || true)" == 200 ]] && break
    [[ "$attempt" -lt 60 ]] || fail "Grafana did not become healthy within the bootstrap deadline"
    sleep 3
done

login=bank11-operator
status="$(call GET "/api/users/lookup?loginOrEmail=$login")"
if [[ "$status" == 404 ]]; then
    status="$(call POST /api/admin/users "{\"name\": \"Bank11 operator\", \"email\": \"operator@bank11.invalid\", \"login\": \"$login\", \"password\": \"$operator_password\"}")"
    expect "$status" "200" "Creating the operator user"
else
    expect "$status" "200" "Looking up the operator user"
fi
id="$(grep -o '"id": *[0-9][0-9]*' "$work/response" | head -n 1 | grep -o '[0-9][0-9]*' || true)"
[[ -n "$id" ]] || fail "Grafana did not return the operator user id"

# Converge a rotated Secret and the read-only role on every run.
status="$(call PUT "/api/admin/users/$id/password" "{\"password\": \"$operator_password\"}")"
expect "$status" "200" "Setting the operator password"
status="$(call PATCH "/api/org/users/$id" '{"role": "Viewer"}')"
expect "$status" "200" "Assigning the Viewer role"

# /api/health может отвечать до окончания file provisioning.
for attempt in $(seq 1 60); do
    status="$(call GET /api/folders/bank11)"
    [[ "$status" == 200 ]] && break
    expect "$status" "404" "Looking up the Bank11 folder"
    [[ "$attempt" -lt 60 ]] || fail "Bank11 folder did not appear within the bootstrap deadline"
    sleep 3
done

# На Grafana 13.2.4 у provisioned-папки пустой ACL: одной роли Viewer недостаточно для чтения её панелей.
status="$(call POST /api/folders/bank11/permissions '{"items": [{"role": "Viewer", "permission": 1}]}')"
expect "$status" "200" "Granting read access to the Bank11 folder"

echo "Grafana operator user $login exists with the Viewer role and Bank11 folder read access"
