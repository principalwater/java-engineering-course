#!/bin/sh
set -eu
umask 077
# Дополняем доверие только внутри контейнера: системные CA сохраняются, TLS-проверки не отключаются.
: > /tmp/bank-jvm.options
if [ -d /run/trust ]; then
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/bank-trust.jks
  for name in ca edge-ca; do
    if ! keytool -importcert -noprompt -alias "bank-$name" -file "/run/trust/$name.crt" \
      -keystore /tmp/bank-trust.jks -storepass changeit >/dev/null 2>&1; then
      echo 'Failed to load the required bank trust certificate' >&2
      exit 1
    fi
  done
  # changeit защищает целостность публичного trust-store; это не пароль сервиса или закрытого ключа.
  printf '%s\n' '-Djavax.net.ssl.trustStore=/tmp/bank-trust.jks' \
    '-Djavax.net.ssl.trustStorePassword=changeit' '-Djavax.net.ssl.trustStoreType=JKS' > /tmp/bank-jvm.options
fi
# emptyDir переживает перезапуск контейнера; старый маркер не должен объявлять новую JVM готовой.
probe_directory=${BANK_PROBES_DIRECTORY:-/tmp/bank-probes}
rm -f "$probe_directory/live" "$probe_directory/ready"
# JDK scrape-worker: по 5 с на запрос/ответ и не более 8 соединений, включая idle.
exec java @/tmp/bank-jvm.options -Djava.security.properties=/app/network.security -Djava.net.preferIPv6Addresses=true \
  -Dsun.net.httpserver.maxReqTime=5 -Dsun.net.httpserver.maxRspTime=5 \
  -Dsun.net.httpserver.idleInterval=10 -Djdk.httpserver.maxConnections=8 \
  -Dsun.net.httpserver.maxReqHeaderSize=8192 -Dsun.net.httpserver.maxReqHeaders=32 \
  -XX:MaxRAMPercentage=45.0 -XX:MaxDirectMemorySize=48m -XX:ActiveProcessorCount=2 \
  -XX:+ExitOnOutOfMemoryError -jar application.jar "$@"
