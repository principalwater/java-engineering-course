#!/bin/sh
# Проба Elasticsearch: "live" проверяет ответ HTTP-слоя, "ready" — обслуживание авторизованных
# запросов. Liveness не зависит от здоровья кластера: перезапуск не исправит переполненный диск.
set -eu
case "${1:-}" in
  live)
    # 401, как и 200, подтверждает работу узла: авторизация для liveness не требуется.
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' http://127.0.0.1:9200/ || true)
    [ "$code" = 200 ] || [ "$code" = 401 ]
    ;;
  ready)
    password=$(cat /run/secrets/elastic_password)
    # Учётные данные передаются через конфигурацию curl в stdin, не попадая в аргументы процесса.
    code=$(printf 'user = "elastic:%s"\n' "$password" | curl -s -o /dev/null -m 4 -K - -w '%{http_code}' \
      'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=2s' || true)
    [ "$code" = 200 ]
    ;;
  *)
    echo "usage: elasticsearch-probe.sh live|ready" >&2
    exit 2
    ;;
esac
