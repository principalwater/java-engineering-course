#!/usr/bin/env bash
set -euo pipefail

# Keep tool versions and vendor checksum verification shared by both Kafka jobs.
mkdir -p "$RUNNER_TEMP/sprint10-tools/bin" "$RUNNER_TEMP/sprint10"
cd "$RUNNER_TEMP/sprint10-tools"
curl --fail --location --retry 3 -o kind https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64
curl --fail --location --retry 3 -o kind.sha256sum https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64.sha256sum
sed 's/kind-linux-amd64/kind/' kind.sha256sum | sha256sum --check
curl --fail --location --retry 3 -O https://get.helm.sh/helm-v4.3.1-linux-amd64.tar.gz
curl --fail --location --retry 3 -O https://get.helm.sh/helm-v4.3.1-linux-amd64.tar.gz.sha256sum
sha256sum --check helm-v4.3.1-linux-amd64.tar.gz.sha256sum
tar -xzf helm-v4.3.1-linux-amd64.tar.gz
curl --fail --location --retry 3 -o kubectl https://dl.k8s.io/release/v1.34.11/bin/linux/amd64/kubectl
curl --fail --location --retry 3 -o kubectl.sha256 https://dl.k8s.io/release/v1.34.11/bin/linux/amd64/kubectl.sha256
printf '%s  kubectl\n' "$(cat kubectl.sha256)" | sha256sum --check
install -m 0755 kind kubectl linux-amd64/helm bin/
echo "$RUNNER_TEMP/sprint10-tools/bin" >> "$GITHUB_PATH"
