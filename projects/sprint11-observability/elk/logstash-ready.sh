#!/bin/bash
# Readiness probe for Logstash: the pipeline is registered with the API and the Logback TCP input accepts
# connections. The connection carries no bytes, so the json_lines codec sees an empty stream and emits
# nothing.
set -eu
curl -sf -m 3 http://127.0.0.1:9600/_node/pipelines/main | grep -q '"main"'
exec 3<>/dev/tcp/127.0.0.1/5044
