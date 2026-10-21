#!/bin/bash
# Готовность Logstash требует регистрации pipeline в API и приёма соединений TCP-входом Logback.
# Проба не передаёт байты: json_lines видит пустой поток и не создаёт событий.
set -eu
curl -sf -m 3 http://127.0.0.1:9600/_node/pipelines/main | grep -q '"main"'
exec 3<>/dev/tcp/127.0.0.1/5044
