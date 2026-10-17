#!/usr/bin/env python3
"""Проверяет Config Server, балансировку, refresh event и остановку обеих JVM через Bus."""
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
GATEWAY = 'http://127.0.0.1:19800'
RABBIT = 'http://127.0.0.1:19473/api'
PORTS = (19888, 19800, 19901, 19902)


def basic(user, password):
    return 'Basic ' + base64.b64encode(f'{user}:{password}'.encode()).decode()


def request(url, method='GET', body=None, headers=None):
    payload = json.dumps(body).encode() if body is not None else None
    try:
        response = urlopen(Request(url, data=payload, method=method,
                                   headers={'Content-Type': 'application/json', **(headers or {})}), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode()


def wait_for(operation, accepted, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = operation()
            if accepted(value):
                return value
        except (URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError('Timed out waiting for the required state')
        time.sleep(0.2)


def main():
    values = dict(line.split('=', 1) for line in (ROOT / 'target/lab.env').read_text().splitlines())
    admin = {'Authorization': basic('operator', values['LAB_OPERATOR_PASSWORD']), 'X-Lab-Operation': 'bus'}
    rabbit_auth = {'Authorization': basic('study', values['RABBITMQ_DEFAULT_PASS'])}
    def rabbit(path, method='GET', body=None):
        status, text = request(RABBIT + path, method, body, rabbit_auth)
        assert 200 <= status < 300, f'RabbitMQ API returned {status}'
        return json.loads(text) if text else None

    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    config = ROOT / 'target/config-repo/property-printer-default.yml'
    config.write_text('text-to-print: "Текст до изменений"\n')
    processes, logs = [], []
    try:
        for port in PORTS:
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', port))
        for module, port in [('config-server', 19888), ('property-printer', 19901),
                             ('property-printer', 19902), ('gateway', 19800)]:
            log = (ROOT / f'target/{module}-{port}.log').open('w')
            logs.append(log)
            process = subprocess.Popen([
                java, '-Xmx192m', '-jar', str(ROOT / module / f'build/libs/{module}.jar'),
                f'--server.port={port}', '--spring.lifecycle.timeout-per-shutdown-phase=5s',
            ], cwd=ROOT, env={**{key: value for key, value in os.environ.items() if key not in values},
                               'LAB_OPERATOR_PASSWORD': values['LAB_OPERATOR_PASSWORD'],
                               **({'RABBITMQ_DEFAULT_PASS': values['RABBITMQ_DEFAULT_PASS']} if module == 'property-printer' else {})},
                stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            wait_for(lambda: request(f'http://127.0.0.1:{port}/actuator/health'), lambda result: result[0] == 200)
            assert process.poll() is None
        printers = processes[1:3]
        old = [request(f'http://127.0.0.1:{port}/texts')[1] for port in (19901, 19902)]
        ids = {text.split(':\n')[0] for text in old}
        assert len(ids) == 2 and all(text.endswith('Текст до изменений') for text in old)
        routed = [request(GATEWAY + '/property-printer/texts') for _ in range(6)]
        assert all(status == 200 for status, _ in routed)
        assert {text.split(':\n')[0] for _, text in routed} == ids
        assert request(GATEWAY + '/property-printer/actuator/env')[0] in (401, 403, 404)
        assert request(GATEWAY + '/property-printer/actuator/busrefresh', 'POST', {}, {'X-Lab-Operation': 'bus'})[0] == 401
        assert request(GATEWAY + '/property-printer/actuator/busrefresh', 'POST', {},
                       {'Authorization': admin['Authorization']})[0] == 403
        print('Two unique Bus IDs, real Gateway load balancing and protected management routes: passed')

        wait_for(lambda: rabbit('/exchanges/%2F/springCloudBus'), lambda value: value['type'] == 'topic')
        rabbit('/queues/%2F/TestQueue', 'PUT', {'durable': True, 'auto_delete': False, 'arguments': {}})
        rabbit('/bindings/%2F/e/springCloudBus/q/TestQueue', 'POST', {'routing_key': '#', 'arguments': {}})
        rabbit('/queues/%2F/TestQueue/contents', 'DELETE')
        config.write_text('text-to-print: "Текст после изменений"\n')
        assert all(request(f'http://127.0.0.1:{port}/texts')[1].endswith('Текст до изменений') for port in (19901, 19902))
        assert request(GATEWAY + '/property-printer/actuator/busrefresh', 'POST', {}, admin)[0] == 204
        for port in (19901, 19902):
            wait_for(lambda: request(f'http://127.0.0.1:{port}/texts'),
                     lambda result: result[0] == 200 and result[1].endswith('Текст после изменений'))
        assert {request(f'http://127.0.0.1:{port}/texts')[1].split(':\n')[0] for port in (19901, 19902)} == ids
        messages = wait_for(lambda: rabbit('/queues/%2F/TestQueue/get', 'POST',
                                           {'count': 100, 'ackmode': 'ack_requeue_true', 'encoding': 'auto'}),
                            lambda value: any('RefreshRemoteApplicationEvent' in message['payload'] for message in value))
        event = next(json.loads(message['payload']) for message in messages if 'RefreshRemoteApplicationEvent' in message['payload'])
        assert event['originService'] in ids and event['destinationService'] == '**' and event['id']
        (ROOT / 'target/refresh-event.json').write_text(json.dumps(event, indent=2))
        print('Both original JVMs refreshed; TestQueue received the real RefreshRemoteApplicationEvent: passed')
        try:
            status, _ = request(GATEWAY + '/property-printer/actuator/busshutdown', 'POST', {}, admin)
            assert status in (204, 500, 502, 504), f'Unexpected shutdown response: {status}'
        except (URLError, TimeoutError, ConnectionError):
            # Контекст закрывается вместе с HTTP-соединением; подтверждение — завершение обеих JVM.
            pass
        for process in printers:
            assert process.wait(timeout=45) == 0, 'Bus must stop both printer JVMs normally'
        for port in (19901, 19902):
            text = (ROOT / f'target/property-printer-{port}.log').read_text()
            assert 'Graceful shutdown complete' in text and 'Received remote shutdown request' in text
        print('Native busshutdown through Gateway stopped both owned JVMs with exit code 0 and graceful shutdown: passed')
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
