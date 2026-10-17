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
for attempt in {1..20}; do
    if timeout 5 "${topics[@]}" --list >/dev/null 2>&1; then
        break
    fi
    if [ "$attempt" = 20 ]; then
        echo 'Kafka authenticated initialization timed out' >&2
        exit 1
    fi
    sleep 1
done
timeout 10 "${topics[@]}" --create --if-not-exists --topic bank.analytics.v1 --partitions 1 --replication-factor 1 \
    --config cleanup.policy=delete --config retention.ms=604800000 --config retention.bytes=268435456 \
    --config segment.bytes=16777216 --config max.message.bytes=32768 --config min.insync.replicas=1
acls=(/opt/kafka/bin/kafka-acls.sh --bootstrap-server kafka:9092 --command-config /tmp/bank-kafka-admin.properties --add --allow-principal User:bank_analytics)
timeout 10 "${acls[@]}" --operation Read --operation Write --operation Describe --topic bank.analytics.v1
for group in bank.analytics.personal.v1 bank.analytics.business.v1; do
    timeout 10 "${acls[@]}" --operation Read --group "$group"
done
timeout 10 "${acls[@]}" --operation IdempotentWrite --cluster
echo 'Kafka bank topic and restricted analytics permissions initialized'
