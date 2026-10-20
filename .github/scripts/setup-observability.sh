#!/usr/bin/env bash
set -euo pipefail

# promtool and amtool ship inside the vendor release archives, so the configuration checks need no container.
# Versions match the Prometheus and Alertmanager images pinned by the Sprint 11 chart.
prometheus_version=3.15.0
alertmanager_version=0.34.1
case "$(uname -s):$(uname -m)" in
    Linux:x86_64) architecture=amd64 ;;
    Linux:aarch64|Linux:arm64) architecture=arm64 ;;
    *) echo 'Observability tools require Linux amd64 or arm64' >&2; exit 1 ;;
esac
tools="$RUNNER_TEMP/bank11-tools"
mkdir -p "$tools/bin"
cd "$tools"

install_vendor_binary() {
    local repository=$1 version=$2 binary=$3
    local archive="$repository-$version.linux-$architecture.tar.gz"
    local release="https://github.com/prometheus/$repository/releases/download/v$version"
    curl --fail --location --retry 3 --output "$archive" "$release/$archive"
    curl --fail --location --retry 3 --output "$repository.sha256sums" "$release/sha256sums.txt"
    # Require exactly one vendor entry; some checksum implementations accept an empty input.
    local checksum_line
    checksum_line="$(awk -v name="$archive" '$2 == name {print; found++} END {if (found != 1) exit 1}' "$repository.sha256sums")"
    printf '%s\n' "$checksum_line" | sha256sum -c -
    tar -xzf "$archive" --strip-components=1 -C bin "${archive%.tar.gz}/$binary"
}

install_vendor_binary prometheus "$prometheus_version" promtool
install_vendor_binary alertmanager "$alertmanager_version" amtool
echo "$tools/bin" >> "$GITHUB_PATH"
