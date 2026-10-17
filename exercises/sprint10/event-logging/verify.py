#!/usr/bin/env python3
"""Реальный обмен, readiness/liveness и сохранение Kafka/PVC после остановки брокера."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))
from kafka_fixture import await_state


def main():
    config = os.environ['KUBECONFIG']
    context = subprocess.check_output(['kubectl', '--kubeconfig', config, 'config', 'current-context'], text=True).strip()
    assert context == 'kind-java-sprint10', 'Verification is limited to the dedicated kind cluster'
    command = ['kubectl', '--kubeconfig', config, '--namespace', 'java-sprint10']
    output = ROOT / 'target/verification'
    output.mkdir(parents=True, exist_ok=True)

    def kube(*args):
        return subprocess.check_output([*command, *args], text=True, timeout=200)

    def resource(kind, name):
        return json.loads(kube('get', kind, name, '-o', 'json'))

    def topic():
        return kube('exec', 'kafka-0', '--', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                    'kafka:9092', '--describe', '--topic', 'log-events')

    def events():
        log = kube('logs', 'deployment/event-logging')
        sent = {(m[1], int(m[2]), int(m[3])) for m in re.finditer(r'EVENT_SENT id=(\S+) partition=(\d+) offset=(\d+)', log)}
        seen = {(m[1], int(m[2]), int(m[3])) for m in re.finditer(r'EVENT_RECEIVED id=(\S+) partition=(\d+) offset=(\d+)', log)}
        (output / 'application.log').write_text(log)
        return sent & seen

    pods = json.loads(kube('get', 'pods', '-l', 'app=event-logging', '-o', 'json'))['items']
    assert len(pods) == 1
    pod = pods[0]['metadata']['name']
    old_pod = resource('pod', pod)
    pvc_uid = resource('pvc', 'data-kafka-0')['metadata']['uid']
    topic_id = re.search(r'TopicId:\s+(\S+)', topic())[1]
    before = await_state(events, lambda rows: len(rows) >= 3, 90)
    preserved = sorted(before)[0]
    health_port = 19593
    with (output / 'port-forward.log').open('w') as log:
        forward = subprocess.Popen([*command, 'port-forward', 'pod/' + pod, f'{health_port}:8080', '--address=127.0.0.1'],
                                   stdout=log, stderr=subprocess.STDOUT)

        def health(group):
            try:
                with urlopen(f'http://127.0.0.1:{health_port}/actuator/health/{group}', timeout=5) as response:
                    return response.status
            except HTTPError as error:
                return error.code
            except (URLError, TimeoutError, ConnectionError):
                assert forward.poll() is None, 'Owned port-forward process exited'
                return 0

        try:
            await_state(lambda: health('readiness'), lambda status: status == 200)
            assert health('liveness') == 200
            old_broker = resource('pod', 'kafka-0')['metadata']['uid']
            kube('scale', 'statefulset/kafka', '--replicas=0')
            kube('wait', '--for=delete', 'pod/kafka-0', '--timeout=90s')
            await_state(lambda: health('readiness'), lambda status: status == 503)
            assert health('liveness') == 200, 'Dependency outage must not kill the application'
            await_state(lambda: resource('pod', pod), lambda p: any(c['type'] == 'Ready' and c['status'] == 'False'
                        for c in p['status']['conditions']))
            unchanged = resource('pod', pod)
            assert unchanged['metadata']['uid'] == old_pod['metadata']['uid']
            assert unchanged['status']['containerStatuses'][0]['restartCount'] == old_pod['status']['containerStatuses'][0]['restartCount']
            print('Broker down: readiness=503, liveness=200, application stays alive: passed', flush=True)
        finally:
            kube('scale', 'statefulset/kafka', '--replicas=1')
            kube('rollout', 'status', 'statefulset/kafka', '--timeout=180s')
            forward.terminate()
            forward.wait(timeout=10)
    assert resource('pod', 'kafka-0')['metadata']['uid'] != old_broker
    assert resource('pvc', 'data-kafka-0')['metadata']['uid'] == pvc_uid
    assert re.search(r'TopicId:\s+(\S+)', topic())[1] == topic_id
    kube('rollout', 'status', 'deployment/event-logging', '--timeout=180s')
    after = await_state(events, lambda rows: bool(rows - before), 90)
    old_record = kube('exec', 'kafka-0', '--', '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server',
                      'kafka:9092', '--topic', 'log-events', '--partition', str(preserved[1]), '--offset', str(preserved[2]),
                      '--max-messages', '1', '--timeout-ms', '10000', '--property', 'print.key=true')
    assert preserved[0] in old_record, 'The acknowledged record must survive broker Pod recreation'
    result = {'matched_before': len(before), 'matched_after': len(after), 'preserved_event': preserved,
              'topic_id_preserved': topic_id, 'pvc_uid_preserved': pvc_uid,
              'outage_readiness': 503, 'outage_liveness': 200}
    (output / 'results.json').write_text(json.dumps(result, indent=2))
    print('New broker Pod keeps topic/PVC/old record and resumes end-to-end delivery: passed', flush=True)
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
