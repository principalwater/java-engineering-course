#!/bin/sh
set -eu
zk_cli() {
    timeout -k 2s 15s zkCli.sh -server discovery:2181 -waitforconnection -timeout 5000 2>&1
}
# Последняя попытка с двумя JVM и принудительным завершением оставляет запас до deadline Job 300s.
deadline=$(( $(date +%s) + 240 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    result=$(printf '%s\n' \
        'create /config' \
        'create /config/application' \
        'create /config/application/bank.client.connect-timeout 1s' \
        'set /config/application/bank.client.connect-timeout 1s' \
        'create /config/application/bank.client.read-timeout 3s' \
        'set /config/application/bank.client.read-timeout 3s' \
        'create /config/application/spring.threads.virtual.enabled true' \
        'set /config/application/spring.threads.virtual.enabled true' \
        'get /config/application/bank.client.connect-timeout' \
        'get /config/application/bank.client.read-timeout' \
        'get /config/application/spring.threads.virtual.enabled' \
        'quit' | zk_cli) || result=''
    # Код CLI относится к последней команде: успешный get скрывает отказ записи другого ключа.
    if printf '%s\n' "$result" | grep -qx '1s' \
        && printf '%s\n' "$result" | grep -qx '3s' \
        && printf '%s\n' "$result" | grep -qx 'true'; then
        # Ревизия открывает старт приложений только после проверки всех общих значений.
        revision=$(printf '%s\n' \
            'create /config/application/bank.config.revision sprint11-v1' \
            'set /config/application/bank.config.revision sprint11-v1' \
            'get /config/application/bank.config.revision' \
            'quit' | zk_cli) || revision=''
        if printf '%s\n' "$revision" | grep -qx 'sprint11-v1'; then
            echo 'Shared bank configuration is ready'
            exit 0
        fi
    fi
    sleep 1
done
echo 'ZooKeeper configuration initialization timed out' >&2
exit 1
