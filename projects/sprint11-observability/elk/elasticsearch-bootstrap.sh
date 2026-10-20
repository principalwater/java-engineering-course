#!/bin/sh
# Idempotent Elasticsearch bootstrap, run as a finite Job with the elastic bootstrap superuser:
# lifecycle policy, index template, roles and the four non-superuser identities. Every call is a PUT/POST
# of the desired state, so re-running after a Secret rotation converges instead of failing.
set -eu
umask 077

es=http://elasticsearch:9200
setup=/setup
secrets=/run/secrets
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail() {
    echo "FAIL $*" >&2
    exit 1
}

read_secret() {
    [ -s "$secrets/$1" ] || fail "Secret file $1 is missing or empty"
    value=$(tr -d '\r\n' < "$secrets/$1")
    # The values are interpolated into JSON documents below, so anything needing escaping is rejected.
    case "$value" in
        *[!A-Za-z0-9._~+=-]*) fail "Secret file $1 contains characters outside A-Za-z0-9._~+=-" ;;
    esac
    [ "${#value}" -ge 16 ] || fail "Secret file $1 must hold at least 16 characters"
    printf '%s' "$value"
}

elastic_password=$(read_secret elastic_password)
# curl reads the credential from a private file in the in-memory work directory, not from its arguments.
printf 'user = "elastic:%s"\n' "$elastic_password" > "$work/auth.conf"
unset elastic_password

# call METHOD PATH: sends the request body from stdin and requires a 2xx answer.
call() {
    method=$1
    path=$2
    status=$(curl -s -m 30 -K "$work/auth.conf" -o "$work/response" -w '%{http_code}' -X "$method" \
        -H 'Content-Type: application/json' --data-binary @- "$es$path" || true)
    case "$status" in
        2??) ;;
        *)
            echo "Elasticsearch $method $path returned HTTP ${status:-none}: $(head -c 400 "$work/response" 2>/dev/null)" >&2
            exit 1
            ;;
    esac
}

echo "Waiting for authenticated Elasticsearch cluster health"
attempt=0
until [ "$(curl -s -m 5 -K "$work/auth.conf" -o /dev/null -w '%{http_code}' "$es/_cluster/health?wait_for_status=yellow&timeout=4s" || true)" = 200 ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 90 ] || fail "Elasticsearch did not become ready within the bootstrap deadline"
    sleep 3
done

retention=${LOGS_RETENTION:-7d}
case "$retention" in
    [1-9]*d) ;;
    *) fail "LOGS_RETENTION must be a whole number of days such as 7d" ;;
esac

# Template and policy come first: the writer identity is created last, so no index can exist before its
# mapping and lifecycle do.
sed "s/@@RETENTION@@/$retention/" "$setup/ilm-policy.json" | call PUT /_ilm/policy/bank11-logs-retention
call PUT /_index_template/bank11-logs < "$setup/index-template.json"

index_read='{"names": ["bank11-logs-*"], "privileges": ["read", "view_index_metadata"]}'
call PUT /_security/role/bank11_logstash_writer <<ROLE
{"cluster": ["monitor"], "indices": [{"names": ["bank11-logs-*"], "privileges": ["create_index", "create_doc", "view_index_metadata"]}]}
ROLE
# Grafana проверяет версию и здоровье кластера; monitor даёт чтение, а индексы ограничены логами банка.
call PUT /_security/role/bank11_log_search <<ROLE
{"cluster": ["monitor"], "indices": [$index_read]}
ROLE
call PUT /_security/role/bank11_log_reader <<ROLE
{"indices": [$index_read], "applications": [{"application": "kibana-.kibana", "privileges": ["read"], "resources": ["space:default"]}]}
ROLE
call PUT /_security/role/bank11_kibana_provisioner <<ROLE
{"indices": [$index_read], "applications": [{"application": "kibana-.kibana", "privileges": ["all"], "resources": ["space:default"]}]}
ROLE

# kibana_system is a reserved user: only its password can be set.
password=$(read_secret kibana_system_password) || exit 1
printf '{"password": "%s"}' "$password" | call POST /_security/user/kibana_system/_password

put_user() {
    password=$(read_secret "$2") || exit 1
    printf '{"password": "%s", "roles": ["%s"], "full_name": "%s", "enabled": true}' \
        "$password" "$3" "$4" | call PUT "/_security/user/$1"
}
put_user bank11_logstash_writer logstash_writer_password bank11_logstash_writer "Logstash log writer"
put_user bank11_grafana grafana_es_reader_password bank11_log_search "Grafana log reader"
put_user bank11_operator elk_operator_password bank11_log_reader "Read-only log operator"
put_user bank11_kibana_provisioner kibana_provisioner_password bank11_kibana_provisioner "Kibana saved object provisioner"

echo "Elasticsearch lifecycle policy (${retention}), index template, roles and users are in place"
