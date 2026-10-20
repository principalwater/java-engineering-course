#!/bin/sh
# Lifecycle probe for the Elasticsearch container: "live" proves the HTTP layer answers, "ready" proves
# the cluster can serve authenticated requests. Liveness must not depend on cluster health, or a full
# disk would restart a node that a restart cannot repair.
set -eu
case "${1:-}" in
  live)
    # 401 is as good as 200: the node is serving, it just does not know us.
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' http://127.0.0.1:9200/ || true)
    [ "$code" = 200 ] || [ "$code" = 401 ]
    ;;
  ready)
    password=$(cat /run/secrets/elastic_password)
    # Credentials travel through a curl config on stdin; they never appear in the process arguments.
    code=$(printf 'user = "elastic:%s"\n' "$password" | curl -s -o /dev/null -m 4 -K - -w '%{http_code}' \
      'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=2s' || true)
    [ "$code" = 200 ]
    ;;
  *)
    echo "usage: elasticsearch-probe.sh live|ready" >&2
    exit 2
    ;;
esac
