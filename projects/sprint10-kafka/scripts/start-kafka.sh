#!/bin/bash
set -euo pipefail
umask 077
admin="$(cat /run/secrets/kafka_admin_password)"
analytics="$(cat /run/secrets/kafka_analytics_password)"
if [[ ! "$admin" =~ ^[a-f0-9]{64}$ || ! "$analytics" =~ ^[a-f0-9]{64}$ ]]; then
    echo 'Kafka requires 64-character hexadecimal secret files' >&2
    exit 1
fi
# JAAS хранится только в tmpfs; KafkaConfig и Docker inspect не содержат паролей.
cat > /tmp/bank-kafka-jaas.conf <<JAAS
KafkaServer {
    org.apache.kafka.common.security.plain.PlainLoginModule required
    username="bank_kafka_admin" password="$admin"
    user_bank_kafka_admin="$admin" user_bank_analytics="$analytics";
};
KafkaClient {
    org.apache.kafka.common.security.plain.PlainLoginModule required
    username="bank_kafka_admin" password="$admin";
};
JAAS
unset admin analytics
export KAFKA_OPTS=-Djava.security.auth.login.config=/tmp/bank-kafka-jaas.conf
# Существующий cluster ID и подтверждённые записи принадлежат persistent volume.
if [ ! -f /var/lib/kafka/data/meta.properties ]; then
    cluster_id="$(/opt/kafka/bin/kafka-storage.sh random-uuid)"
    /opt/kafka/bin/kafka-storage.sh format --cluster-id "$cluster_id" --config /setup/kafka/server.properties
fi
exec /opt/kafka/bin/kafka-server-start.sh /setup/kafka/server.properties
