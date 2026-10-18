#!/bin/bash
set -euo pipefail
umask 077
admin="$(cat /run/secrets/kafka_admin_password)"
if [[ ! "$admin" =~ ^[a-f0-9]{64}$ ]]; then
    echo 'Kafka requires a 64-character hexadecimal admin secret file' >&2
    exit 1
fi
# CLI читает пароль из tmpfs-файла, а не из argv или Docker environment.
cat > /tmp/bank-kafka-admin.properties <<PROPERTIES
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="bank_kafka_admin" password="$admin";
request.timeout.ms=2000
default.api.timeout.ms=3000
PROPERTIES
unset admin
trap 'rm -f /tmp/bank-kafka-admin.properties' EXIT
topics=(/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /tmp/bank-kafka-admin.properties)
# Бюджет процесса включает холодный старт JVM: при 0.25 CPU CLI занимает 9 с, хотя RPC ограничен 3 с.
readonly CLI_DEADLINE_SECONDS=15
# Восемь попыток ограничивают ожидание брокера примерно двумя минутами, оставляя бюджет на topic и ACL.
readonly MAX_PROBES=8
for ((attempt = 1; attempt <= MAX_PROBES; attempt++)); do
    if timeout "$CLI_DEADLINE_SECONDS" "${topics[@]}" --list >/dev/null 2>&1; then
        break
    else
        probe_status=$?
    fi
    if [ "$attempt" = "$MAX_PROBES" ]; then
        echo "Kafka authenticated initialization timed out (last probe exit: $probe_status)" >&2
        exit 1
    fi
    sleep 1
done
timeout "$CLI_DEADLINE_SECONDS" "${topics[@]}" --create --if-not-exists --topic bank.analytics.v1 --partitions 1 --replication-factor 1 \
    --config cleanup.policy=delete --config retention.ms=604800000 --config retention.bytes=268435456 \
    --config segment.bytes=16777216 --config max.message.bytes=32768 --config min.insync.replicas=1
acls=(/opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config /tmp/bank-kafka-admin.properties --add --allow-principal User:bank_analytics)
timeout "$CLI_DEADLINE_SECONDS" "${acls[@]}" --operation Read --operation Write --operation Describe --topic bank.analytics.v1
for group in bank.analytics.personal.v1 bank.analytics.business.v1; do
    timeout "$CLI_DEADLINE_SECONDS" "${acls[@]}" --operation Read --group "$group"
done
timeout "$CLI_DEADLINE_SECONDS" "${acls[@]}" --operation IdempotentWrite --cluster
echo 'Kafka bank topic and restricted analytics permissions initialized'
