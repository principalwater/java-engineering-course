#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${KUBECONFIG:?Set a separate kubeconfig for kind-java-sprint10}"
[[ "$(kubectl --kubeconfig "$KUBECONFIG" config current-context)" == kind-java-sprint10 ]] || {
    echo 'Expected the dedicated kind-java-sprint10 context' >&2; exit 1;
}
jar=build/libs/event-logging-service.jar
[[ -f "$jar" ]] || { echo 'Build bootJar first' >&2; exit 1; }
image="event-logging-service:sprint10-$(shasum -a 256 "$jar" | cut -c 1-12)"
docker build -t "$image" .
kind load docker-image --name java-sprint10 "$image"
helm lint charts/kafka charts/event-logging
helm template kafka charts/kafka --namespace java-sprint10 > /dev/null
helm template event-logging charts/event-logging --namespace java-sprint10 --set-string image="$image" > /dev/null
helm upgrade --install kafka charts/kafka --kubeconfig "$KUBECONFIG" --namespace java-sprint10 \
    --create-namespace --wait --timeout 5m
helm upgrade --install event-logging charts/event-logging --kubeconfig "$KUBECONFIG" --namespace java-sprint10 \
    --set-string image="$image" --wait --timeout 5m
