#!/bin/sh
# Запускает официальный inbound-агент только после того, как выделенный Docker-демон отвечает по TLS.
# Демон живёт в соседнем контейнере с общим сетевым namespace, поэтому адрес и клиентские
# сертификаты берутся из окружения контейнера.
set -eu

authority=/run/bank-jenkins/tls_ca.pem
if [ ! -s "$authority" ]; then
    echo 'The controller CA certificate is missing from the agent secret mount' >&2
    exit 1
fi

deadline=$(( $(date +%s) + ${BANK_DOCKER_WAIT_SECONDS:-180} ))
until docker version --format '{{.Server.Version}}' > /dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo 'The dedicated Docker daemon did not become ready in time' >&2
        exit 1
    fi
    sleep 2
done

# Remoting доверяет для соединения с контроллером только этому CA. Сертификат передаётся значением, потому что
# разбор аргументов раскрывает @file в отдельный аргумент на каждую строку и разрезал бы PEM-файл.
exec /usr/local/bin/jenkins-agent -cert "$(cat "$authority")" "$@"
