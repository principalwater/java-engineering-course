#!/usr/bin/env bash
set -euo pipefail

pipeline_file=${1:-projects/sprint10-kafka/Jenkinsfile}
# The official validator includes Declarative Pipeline plugins; no public Jenkins service is used.
image=ghcr.io/jenkinsci/jenkinsfilelint-server@sha256:44eeba4e88a7c76bba4839a8a5111da90d64795c8a0600e7eec4b5e9610e375c
container=$(docker run --rm --detach --memory=1g --publish 127.0.0.1::8080 "$image")
trap 'docker stop "$container" >/dev/null 2>&1 || true' EXIT
port=$(docker port "$container" 8080 | sed 's/.*://')
endpoint="http://127.0.0.1:$port/pipeline-model-converter/validate"
for attempt in $(seq 1 60); do
    if response=$(curl --fail --silent --max-time 5 --form "jenkinsfile=<$pipeline_file" "$endpoint"); then
        printf '%s\n' "$response"
        grep --quiet --fixed-strings --line-regexp 'Jenkinsfile successfully validated.' <<< "$response"
        exit 0
    fi
    sleep 2
done
echo 'Jenkins validation service did not become available' >&2
exit 1
