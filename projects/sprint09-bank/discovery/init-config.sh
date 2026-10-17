#!/bin/sh
set -eu
attempt=0
while [ "$attempt" -lt 30 ]; do
    attempt=$((attempt + 1))
    result=$(printf '%s\n' \
        'create /config' \
        'create /config/application' \
        'create /config/application/bank.config.revision sprint09-v1' \
        'set /config/application/bank.config.revision sprint09-v1' \
        'create /config/application/bank.client.connect-timeout 1s' \
        'set /config/application/bank.client.connect-timeout 1s' \
        'create /config/application/bank.client.read-timeout 3s' \
        'set /config/application/bank.client.read-timeout 3s' \
        'create /config/application/spring.threads.virtual.enabled true' \
        'set /config/application/spring.threads.virtual.enabled true' \
        'get /config/application/bank.config.revision' \
        'quit' | timeout 8s zkCli.sh -server discovery:2181 -timeout 3000 2>&1) || result=''
    if printf '%s\n' "$result" | grep -qx sprint09-v1; then
        echo 'Shared bank configuration is ready'
        exit 0
    fi
    sleep 1
done
echo 'ZooKeeper configuration initialization timed out' >&2
exit 1
