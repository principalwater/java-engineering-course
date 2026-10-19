"""Verify real Kafka TLS/ACLs, retained PVCs and the dedicated HA quorum in the owned cluster."""

import argparse
import base64
import json
from pathlib import Path
import re
import shlex
import sys
import time
import uuid

import yaml

from runtime import command, kubectl, owned_identity, private_file, project_dir, save_receipt


CLIENT = r'''set -euo pipefail
umask 077
phase=setup
diagnostic=/tmp/setup
trap 'result=$?
if [ "$result" -ne 0 ]; then
    category=
    if [ -f "$diagnostic" ]; then
        category="$(grep -Eo "SSLHandshakeException|SSLAuthenticationException|TopicAuthorizationException|GroupAuthorizationException|TimeoutException" "$diagnostic" | head -1 || true)"
    fi
    printf "BANK_PROBE_FAILURE %s %s %s\n" "$phase" "$result" "${category:-Other}"
fi
exit "$result"' EXIT
password="$(cat /run/client/password)"
password="${password//\\/\\\\}"
password="${password//\"/\\\"}"
{
    printf '%s\n' 'security.protocol=SASL_SSL' 'sasl.mechanism=SCRAM-SHA-512'
    printf 'sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="%s" password="%s";\n' USERNAME "$password"
    printf '%s\n' 'ssl.truststore.type=PEM' 'ssl.truststore.location=/run/ca/ca.crt' 'ssl.endpoint.identification.algorithm=https'
    # Cold, CPU-limited CLI clients need TLS/SCRAM and metadata time, independently of application deadlines.
    printf '%s\n' 'request.timeout.ms=5000' 'default.api.timeout.ms=15000' 'max.block.ms=15000' 'delivery.timeout.ms=15000' 'linger.ms=0' 'acks=all' 'enable.idempotence=true'
} > /tmp/client.properties
unset password
bootstrap=bank-kafka-kafka-bootstrap:9093
'''


def resource_ready(identity: dict, kind: str, name: str, *, timeout: int = 300) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = kubectl(identity, "get", kind, name, "--ignore-not-found", "-o", "json")
        if raw:
            resource = json.loads(raw)
            if any(condition["type"] == "Ready" and condition["status"] == "True"
                   for condition in resource.get("status", {}).get("conditions", [])):
                return resource
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for owned {kind}/{name}")


def job(identity: dict, user: str, body: str) -> None:
    name = f"bank10-probe-{uuid.uuid4().hex[:12]}"
    chart = yaml.safe_load((project_dir() / "charts/bank/charts/kafka/values.yaml").read_text(encoding="utf-8"))
    labels = {"app.kubernetes.io/part-of": "bank10", "bank10.io/role": "probe", "bank10.io/probe": name}
    manifest = {"apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": name, "labels": labels},
                "spec": {"backoffLimit": 0, "activeDeadlineSeconds": 150, "template": {
                    "metadata": {"labels": labels}, "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                        "securityContext": {"runAsUser": 10001, "runAsGroup": 10001, "runAsNonRoot": True,
                                            "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
                        "containers": [{"name": "client", "image": chart["image"], "command": ["bash", "-c", CLIENT.replace("USERNAME", shlex.quote(user)) + body],
                            "env": [{"name": "KAFKA_HEAP_OPTS", "value": "-Xms32m -Xmx96m -XX:ActiveProcessorCount=1"}],
                            "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}},
                            "resources": {"requests": {"cpu": "50m", "memory": "96Mi"}, "limits": {"cpu": "500m", "memory": "192Mi"}},
                            "volumeMounts": [{"name": "client", "mountPath": "/run/client", "readOnly": True},
                                             {"name": "ca", "mountPath": "/run/ca", "readOnly": True}, {"name": "tmp", "mountPath": "/tmp"}]}],
                        "volumes": [{"name": "client", "secret": {"secretName": user, "defaultMode": 288, "items": [{"key": "password", "path": "password"}]}},
                                    {"name": "ca", "secret": {"secretName": "bank-kafka-cluster-ca-cert", "defaultMode": 292, "items": [{"key": "ca.crt", "path": "ca.crt"}]}},
                                    {"name": "tmp", "emptyDir": {"medium": "Memory", "sizeLimit": "64Mi"}}]}}}}
    bootstrap = json.loads(kubectl(identity, "get", "service", "bank-kafka-kafka-bootstrap", "-o", "json"))
    manifest["spec"]["template"]["spec"]["hostAliases"] = [
        {"ip": bootstrap["spec"]["clusterIP"], "hostnames": ["bank10-wrong-kafka.invalid"]}]
    created = json.loads(kubectl(identity, "create", "-f", "-", "-o", "json", data=json.dumps(manifest).encode()))
    deadline = time.monotonic() + 180
    try:
        while time.monotonic() < deadline:
            state = json.loads(kubectl(identity, "get", "job", name, "-o", "json"))
            if state.get("status", {}).get("succeeded", 0) or state.get("status", {}).get("failed", 0):
                break
            time.sleep(2)
        logs = kubectl(identity, "logs", f"job/{name}", "--limit-bytes=16384").encode()
        password = base64.b64decode(json.loads(kubectl(identity, "get", "secret", user, "-o", "json"))["data"]["password"])
        if password in logs:
            raise RuntimeError("Kafka probe log contained a credential; output suppressed")
        if not state.get("status", {}).get("succeeded", 0):
            diagnostic = Path(identity["kubeconfig"]).parent / f"{name}.failure.log"
            private_file(diagnostic, logs)
            failed = re.search(rb"(?m)^BANK_PROBE_FAILURE ([a-z-]{1,32}) ([0-9]{1,3}) ([A-Za-z]{1,40})\r?$", logs)
            reason = (f"phase={failed[1].decode()}, exit={failed[2].decode()}, category={failed[3].decode()}"
                      if failed else "phase=unknown")
            raise RuntimeError(f"Kafka probe for {user} failed: {reason}; private diagnostic: {diagnostic}")
    finally:
        current = json.loads(kubectl(identity, "get", "job", name, "-o", "json"))
        if current["metadata"]["uid"] != created["metadata"]["uid"] or current["metadata"]["labels"].get("bank10.io/probe") != name:
            raise ValueError("Probe Job ownership changed; refusing cleanup")
        kubectl(identity, "delete", "job", name, "--wait=true", "--timeout=60s")


def security(identity: dict, analytics: bool) -> None:
    cases = {"accounts": ("bank.accounts.notifications.v1", "bank.cash.notifications.v1", "bank.notifications.receipts.v1"),
             "cash": ("bank.cash.notifications.v1", "bank.accounts.notifications.v1", None),
             "transfer": ("bank.transfer.notifications.v1", "bank.accounts.notifications.v1", None),
             "notifications": ("bank.notifications.receipts.v1", "bank.accounts.analytics.v1", "bank.accounts.notifications.v1")}
    if analytics:
        cases["analytics"] = ("bank.analytics.v1", "bank.accounts.notifications.v1", "bank.accounts.analytics.v1")
    for service, (allowed, forbidden, consumer_topic) in cases.items():
        resource_ready(identity, "kafkauser", f"{service}-service")
        # TopicCommand also requests DescribeConfigs, which application principals do not need.
        topic_pattern = allowed.replace(".", r"\.")
        script = f'''phase=authorized-topic; diagnostic=/tmp/describe
timeout 30s /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server "$bootstrap" --command-config /tmp/client.properties --topic {shlex.quote(allowed)} >/tmp/describe 2>&1
test "$(grep -Ec '^{topic_pattern}:[0-9]+:[0-9]+$' /tmp/describe)" -eq 6
phase=forbidden-write; diagnostic=/tmp/denied
printf '%s\\n' 'bank10-forbidden-probe' | timeout 30s /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$bootstrap" --producer.config /tmp/client.properties --sync --topic {shlex.quote(forbidden)} >/tmp/denied 2>&1 || true
grep -q TopicAuthorizationException /tmp/denied
echo 'Kafka TLS/SCRAM and foreign-write denial verified'
'''
        if consumer_topic:
            script += f'''phase=forbidden-group; diagnostic=/tmp/group-denied
timeout --kill-after=5s 20s /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" --consumer.config /tmp/client.properties --consumer-property enable.auto.commit=false --group bank10-forbidden-probe --max-messages 1 --topic {shlex.quote(consumer_topic)} >/tmp/group-denied 2>&1 || true
grep -q GroupAuthorizationException /tmp/group-denied
echo 'Foreign consumer group denied on an authorized input topic'
'''
        if service == "accounts":
            script += f'''phase=hostname-mismatch; diagnostic=/tmp/hostname-denied
timeout 30s /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server bank10-wrong-kafka.invalid:9093 --command-config /tmp/client.properties --topic {shlex.quote(allowed)} >/tmp/hostname-denied 2>&1 || true
grep -Eq 'subject alternative|No name matching' /tmp/hostname-denied
echo 'Kafka hostname mismatch rejected with the same trusted CA'
'''
        job(identity, f"{service}-service", script)
    print(f"Kafka TLS/hostname/SCRAM, cross-service writes and foreign consumer groups passed for {len(cases)} principals")


def pvc_snapshot(identity: dict) -> dict:
    claims = json.loads(kubectl(identity, "get", "pvc", "-o", "json"))["items"]
    return {item["metadata"]["name"]: {"uid": item["metadata"]["uid"], "volume": item["spec"]["volumeName"]}
            for item in claims if item["metadata"].get("labels", {}).get("strimzi.io/cluster") == "bank-kafka"
            or item["metadata"].get("labels", {}).get("app.kubernetes.io/part-of") == "bank10"}


def probe_records(identity: dict, user: str, topic: str, group: str, messages: tuple[str, ...], *, publish: bool) -> None:
    script = ""
    if publish:
        # Metadata wait and delivery can each take 15s; leave room for JVM startup and shutdown.
        script += f'''phase=publish; diagnostic=/tmp/publish
printf '%s\\n' {shlex.quote(messages[-1])} | timeout --kill-after=5s 40s /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$bootstrap" --producer.config /tmp/client.properties --sync --topic {shlex.quote(topic)} >/tmp/publish 2>&1
if grep -Eq 'ERROR|Exception' /tmp/publish; then exit 1; fi
'''
    script += f'''phase=consume; diagnostic=/tmp/consumer
timeout 35s /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" --consumer.config /tmp/client.properties --group {shlex.quote(group)} --from-beginning --max-messages {len(messages)} --timeout-ms 20000 --topic {shlex.quote(topic)} >/tmp/received 2>/tmp/consumer
test "$(wc -l < /tmp/received)" -eq {len(messages)}
'''
    # ConsoleConsumer can return zero after a timeout; validate actual records, not only its exit code.
    for message in messages:
        script += f"grep -Fxq {shlex.quote(message)} /tmp/received\n"
    script += "echo 'All retained probe records verified'\n"
    job(identity, user, script)


def persistence(identity: dict, state_dir: Path, *, ha: bool) -> None:
    prefix = f"bank10-probe-{uuid.uuid4().hex[:10]}"
    labels = {"strimzi.io/cluster": "bank-kafka", "bank10.io/probe": prefix}
    topic = {"apiVersion": "kafka.strimzi.io/v1", "kind": "KafkaTopic", "metadata": {"name": prefix, "labels": labels},
             "spec": {"topicName": prefix, "partitions": 6, "replicas": 3 if ha else 1,
                      "config": {"min.insync.replicas": 2 if ha else 1, "retention.ms": 3600000}}}
    user = {"apiVersion": "kafka.strimzi.io/v1", "kind": "KafkaUser", "metadata": {"name": prefix, "labels": labels},
            "spec": {"authentication": {"type": "scram-sha-512"}, "authorization": {"type": "simple", "acls": [
                {"resource": {"type": "topic", "name": prefix, "patternType": "literal"}, "operations": ["Read", "Write", "Describe"]},
                *[{"resource": {"type": "group", "name": prefix + suffix, "patternType": "literal"}, "operations": ["Read"]}
                  for suffix in ("-before", "-degraded", "-after")],
                {"resource": {"type": "cluster"}, "operations": ["IdempotentWrite"]}]}}}
    created = []
    cordoned = None
    stopped_worker = None
    before = pvc_snapshot(identity)
    try:
        for resource in (topic, user):
            result = json.loads(kubectl(identity, "create", "-f", "-", "-o", "json", data=json.dumps(resource).encode()))
            created.append((resource["kind"], result["metadata"]["uid"]))
            resource_ready(identity, resource["kind"], prefix)
        messages = (f"retained-{prefix}",)
        probe_records(identity, prefix, prefix, prefix + "-before", messages, publish=True)
        pods = json.loads(kubectl(identity, "get", "pods", "-l", "strimzi.io/cluster=bank-kafka,strimzi.io/broker-role=true", "-o", "json"))["items"]
        targets = sorted(pods, key=lambda pod: pod["metadata"]["name"])
        if ha:
            targets = [pod for pod in targets if pod["spec"].get("nodeName") in identity["nodes"]
                       and pod["spec"]["nodeName"] != identity["cluster"] + "-control-plane"]
            if not targets:
                raise ValueError("HA proof requires a broker on an exact owned non-control-plane worker")
        target = targets[0]
        if ha:
            if len(pods) != 3:
                raise ValueError("HA proof requires three dedicated brokers")
            worker = target["spec"]["nodeName"]
            if worker not in identity["nodes"] or worker == identity["cluster"] + "-control-plane":
                raise ValueError("Broker is outside the owned worker inventory")
            node = json.loads(kubectl(identity, "get", "node", worker, "-o", "json"))
            if node.get("spec", {}).get("unschedulable", False):
                raise ValueError("Worker was already cordoned; refusing to change its maintenance state")
            controllers = json.loads(kubectl(identity, "get", "pods", "-l", "strimzi.io/cluster=bank-kafka,strimzi.io/controller-role=true", "-o", "json"))["items"]
            candidates = [pod for pod in controllers if pod["spec"]["nodeName"] == worker]
            if len(controllers) != 3 or len(candidates) != 1 or candidates[0]["metadata"]["name"] == target["metadata"]["name"]:
                raise ValueError("HA proof requires dedicated controller/broker pools on three workers")
            kubectl(identity, "cordon", worker)
            cordoned = worker
            stopped_worker = worker
            command(["docker", "stop", "--time", "30", identity["nodes"][worker]], timeout=90)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                node = json.loads(kubectl(identity, "get", "node", worker, "-o", "json"))
                if not any(item["type"] == "Ready" and item["status"] == "True"
                           for item in node.get("status", {}).get("conditions", [])):
                    survivors = json.loads(kubectl(identity, "get", "pods", "-l", "strimzi.io/cluster=bank-kafka", "-o", "json"))["items"]
                    healthy = [pod for pod in survivors if pod["spec"].get("nodeName") != worker
                               and any(item["type"] == "Ready" and item["status"] == "True"
                                       for item in pod.get("status", {}).get("conditions", []))]
                    if all(sum(pod["metadata"].get("labels", {}).get(f"strimzi.io/{role}-role") == "true"
                               for pod in healthy) == 2 for role in ("broker", "controller")):
                        break
                time.sleep(2)
            else:
                raise RuntimeError("Worker outage did not leave exactly two ready brokers/controllers in the bounded interval")
            messages += (f"degraded-{prefix}",)
            probe_records(identity, prefix, prefix, prefix + "-degraded", messages, publish=True)
            command(["docker", "start", identity["nodes"][worker]], timeout=90)
            stopped_worker = None
            kubectl(identity, "wait", "--for=condition=Ready", "node/" + worker, "--timeout=180s", timeout=200)
            kubectl(identity, "uncordon", worker)
            cordoned = None
            resource_ready(identity, "pod", candidates[0]["metadata"]["name"])
        else:
            kubectl(identity, "delete", "pod", target["metadata"]["name"], "--wait=true", "--timeout=120s", timeout=150)
        resource_ready(identity, "pod", target["metadata"]["name"])
        probe_records(identity, prefix, prefix, prefix + "-after", messages, publish=False)
        after = pvc_snapshot(identity)
        if before != after:
            raise ValueError("Persistent claim identities changed during the Kafka recovery proof")
        save_receipt(state_dir / "pvc-receipt.json", after)
        print(f"Kafka {'dedicated quorum RF3/minISR2' if ha else 'broker restart'} and retained records passed; {len(after)} PVC identities unchanged")
    finally:
        if stopped_worker:
            command(["docker", "start", identity["nodes"][stopped_worker]], timeout=90)
            kubectl(identity, "wait", "--for=condition=Ready", "node/" + stopped_worker, "--timeout=180s", timeout=200)
        if cordoned:
            kubectl(identity, "uncordon", cordoned)
        for kind, uid in reversed(created):
            current = json.loads(kubectl(identity, "get", kind, prefix, "-o", "json"))
            if current["metadata"]["uid"] != uid or current["metadata"].get("labels", {}).get("bank10.io/probe") != prefix:
                raise ValueError("Probe object ownership changed; refusing cleanup")
            kubectl(identity, "delete", kind, prefix, "--wait=true", "--timeout=120s", timeout=150)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("operation", choices=("security", "persistence"))
    args = parser.parse_args()
    identity = owned_identity(args.state_dir)
    source = json.loads((args.state_dir / "source.json").read_text(encoding="utf-8"))
    if args.operation == "security":
        security(identity, source["analytics"])
    else:
        persistence(identity, args.state_dir, ha=identity["profile"] == "ha")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
