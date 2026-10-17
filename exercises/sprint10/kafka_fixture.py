"""Общие CLI-проверки собственного двухброкерного Kafka-стенда."""
from pathlib import Path
import subprocess
import time

BROKER = Path(__file__).resolve().parent / 'broker/compose.yml'


def broker_cli(script, *args, body=None):
    command = ['docker', 'compose', '-p', 'java-sprint10-kafka', '-f', str(BROKER), 'exec', '-T',
               'broker1', '/opt/kafka/bin/' + script, '--bootstrap-server', 'broker1:9092', *args]
    return subprocess.run(command, input=body, capture_output=True, text=True, timeout=30, check=True).stdout


def await_state(operation, accepted, timeout=45):
    deadline = time.monotonic() + timeout
    while True:
        value = operation()
        if accepted(value):
            return value
        if time.monotonic() >= deadline:
            raise AssertionError('Timed out waiting for required Kafka state')
        time.sleep(.2)


def describe(group, *args):
    return broker_cli('kafka-consumer-groups.sh', '--group', group, '--describe', *args).splitlines()


def members(group):
    return [(fields[3], int(fields[4])) for line in describe(group, '--members', '--verbose')
            if len(fields := line.split()) >= 5 and fields[0] == group]


def offsets(group, topic='kafka-mystery'):
    return {int(fields[2]): -1 if fields[3] == '-' else int(fields[3]) for line in describe(group)
            if len(fields := line.split()) >= 5 and fields[0] == group and fields[1] == topic}
