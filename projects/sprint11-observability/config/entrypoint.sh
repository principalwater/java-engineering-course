#!/bin/sh
set -eu
# emptyDir survives a container restart; old readiness must not admit a new JVM.
probe_directory=${BANK_PROBES_DIRECTORY:-/tmp/bank-probes}
rm -f "$probe_directory/live" "$probe_directory/ready"
exec java -Djava.security.properties=/app/network.security \
  -XX:MaxRAMPercentage=45.0 -XX:MaxDirectMemorySize=48m -XX:ActiveProcessorCount=2 \
  -XX:+ExitOnOutOfMemoryError -jar application.jar "$@"
