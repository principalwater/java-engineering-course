#!/bin/bash
# Idempotent Kibana bootstrap, run as a finite Job: the Data View, the default Data View and the saved
# searches, visualizations and dashboard. Objects are written with overwrite=true under fixed ids, so a
# re-run restores the shipped definition instead of duplicating it.
set -euo pipefail
umask 077

kibana=http://kibana:5601
setup=/setup
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
    echo "FAIL $*" >&2
    exit 1
}

file=/run/secrets/kibana_provisioner_password
[[ -s "$file" ]] || fail "Secret file kibana_provisioner_password is missing or empty"
# curl reads the credential from a private file in the in-memory work directory, not from its arguments.
printf 'user = "bank11_kibana_provisioner:%s"\n' "$(tr -d '\r\n' < "$file")" > "$work/auth.conf"

# call METHOD PATH [BODY_FILE]: 5xx and connection failures are retried (Kibana restarts, saved-object
# migrations); a 4xx is a permanent answer, typically a missing privilege, and fails the Job at once rather
# than prompting a wider grant.
call() {
    local method=$1 path=$2 body=${3:-} attempt status
    local -a data=()
    [[ -z "$body" ]] || data=(-H 'Content-Type: application/json' --data-binary "@$body")
    for attempt in $(seq 1 20); do
        status="$(curl -s -m 30 -K "$work/auth.conf" -H 'kbn-xsrf: bank11' -o "$work/response" -w '%{http_code}' \
            -X "$method" ${data[@]+"${data[@]}"} "$kibana$path" || true)"
        case "$status" in
            2??) return 0 ;;
            4??) fail "Kibana $method $path returned HTTP $status: $(head -c 400 "$work/response")" ;;
        esac
        sleep 3
    done
    fail "Kibana $method $path did not succeed (last HTTP ${status:-none})"
}

echo "Waiting for Kibana to report an available overall status"
for attempt in $(seq 1 100); do
    level="$(curl -s -m 5 -K "$work/auth.conf" "$kibana/api/status" | sed -n 's/.*"overall": *{ *"level": *"\([a-z]*\)".*/\1/p' || true)"
    [[ "$level" == available ]] && break
    [[ "$attempt" -lt 100 ]] || fail "Kibana did not become available within the bootstrap deadline"
    sleep 3
done

call POST /api/data_views/data_view "$setup/kibana-data-view.json"
printf '{"data_view_id": "bank11-logs", "force": true}' > "$work/default.json"
call POST /api/data_views/default "$work/default.json"

count=0
while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    kind="$(sed -n 's/^{"type":"\([a-z-]*\)","id":"\([A-Za-z0-9_-]*\)",.*/\1/p' <<< "$line")"
    id="$(sed -n 's/^{"type":"\([a-z-]*\)","id":"\([A-Za-z0-9_-]*\)",.*/\2/p' <<< "$line")"
    [[ -n "$kind" && -n "$id" ]] || fail "kibana-objects.ndjson line does not start with a type/id pair"
    # Drop the type/id pair: the create API takes only attributes and references in its body.
    # shellcheck disable=SC2001
    sed 's/^{"type":"[a-z-]*","id":"[A-Za-z0-9_-]*",/{/' <<< "$line" > "$work/object.json"
    call POST "/api/saved_objects/$kind/$id?overwrite=true" "$work/object.json"
    count=$((count + 1))
done < "$setup/kibana-objects.ndjson"

echo "Kibana Data View bank11-logs and $count saved objects are in place"
