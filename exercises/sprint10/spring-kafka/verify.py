#!/usr/bin/env python3
"""Проверка двух JAR, реального retry/DLQ, отказа БД и повторной доставки."""
import csv
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))
from kafka_fixture import await_state, broker_cli, members, offsets


def main():
    run = uuid4().hex[:10]
    output = ROOT / 'target/verification' / run
    output.mkdir(parents=True)
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    processes, handles = [], []

    def start(module, profile, name, group='', *options):
        path = output / (name + '.log')
        handle = path.open('w')
        handles.append(handle)
        process = subprocess.Popen([str(java), '-Xmx192m', '-jar', str(ROOT / module / 'build/libs' / (module + '.jar')),
                                    '--spring.profiles.active=' + profile, *options], cwd=ROOT,
                                   env={**os.environ, 'STUDY_GROUP_ID': group}, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        return process, path

    def stop(process):
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)

    def ready(group, partitions):
        await_state(lambda: members(group), lambda rows: len(rows) == 3 and sum(n for _, n in rows) == partitions, 90)

    def sent(path, kind):
        return {(m[1], int(m[2]), int(m[3])) for line in path.read_text().splitlines()
                if (m := re.search(kind + r'_SENT id=(\S+) partition=(\d+) offset=(\d+)', line))}

    def wait_log(path, text):
        return await_state(path.read_text, lambda log: text in log, 60)

    def ends():
        return {int(row.split(':')[1]): int(row.split(':')[2])
                for row in broker_cli('kafka-get-offsets.sh', '--topic', 'orders', '--time', '-1').splitlines()}

    def inject(key, value):
        before = ends()
        broker_cli('kafka-console-producer.sh', '--topic', 'orders', '--property', 'parse.key=true',
                   '--property', 'key.separator=|', '--sync', '--producer-property', 'acks=all', body=key + '|' + value + '\n')
        after = ends()
        changed = [p for p in before if after[p] == before[p] + 1]
        assert len(changed) == 1, 'Fixture needs one acknowledged record and no other producer'
        return changed[0], before[changed[0]]

    def order(scenario):
        while True:
            value = uuid4()
            signed = int.from_bytes(value.bytes[8:], 'big', signed=True)
            if signed % 10 == scenario:
                return str(value)

    def inject_order(key):
        UUID(key)
        return inject(key, json.dumps({'id': key, 'username': 'anonymous', 'createdAt': '2026-10-17T12:00:00Z'}))

    def export():
        wrapper = ROOT.parents[1] / 'sprint02/file-transformer-gradle-plugin/gradlew'
        subprocess.run([str(wrapper), '--no-daemon', '--console=plain', '-p', str(ROOT), ':consumer:exportDlq'],
                       cwd=ROOT, check=True, timeout=90, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        with (ROOT / 'target/dead-letter-queue.csv').open() as file:
            return {row['ID']: row for row in csv.DictReader(file)}

    def record_id(position):
        return f'orders:{position[0]}:{position[1]}'

    try:
        group = 'uuid-' + run
        producer, producer_log = start('producer', 'uuid', 'uuid-producer')
        consumer, consumer_log = start('consumer', 'uuid', 'uuid-consumer', group)
        ready(group, 3)
        await_state(lambda: sent(producer_log, 'UUID'), lambda rows: len(rows) >= 5)
        stop(producer)
        expected = sent(producer_log, 'UUID')
        await_state(consumer_log.read_text, lambda log: all('UUID_RECEIVED id=' + key in log for key, _, _ in expected))
        stop(consumer)
        print('Separate producer/consumer JARs deliver acknowledged UUIDs: passed', flush=True)

        group = 'orders-' + run
        consumer, consumer_log = start('consumer', 'orders', 'orders-consumer', group)
        ready(group, 5)
        producer, producer_log = start('producer', 'orders', 'orders-producer')
        await_state(lambda: sent(producer_log, 'ORDER'), lambda rows: len(rows) >= 5)
        stop(producer)
        random_records = sent(producer_log, 'ORDER')
        await_state(consumer_log.read_text, lambda log: all('ORDER_OK id=' + key in log or
                    f'DLQ_STORED topic=orders partition={p} offset={o} ' in log for key, p, o in random_records))

        controlled, positions = {}, {}
        for scenario in (0, 6, 8, 9):
            key = order(scenario)
            position = inject_order(key)
            controlled[scenario], positions[scenario] = key, position
            if scenario in (0, 6):
                wait_log(consumer_log, f'ORDER_OK id={key} attempt={1 if scenario == 0 else 3} ')
            else:
                wait_log(consumer_log, f'DLQ_STORED topic=orders partition={position[0]} offset={position[1]} ')
        malformed = inject('malformed-' + run, 'PRIVATE_PAYLOAD_' + run)
        wait_log(consumer_log, f'DLQ_STORED topic=orders partition={malformed[0]} offset={malformed[1]} ')
        text = consumer_log.read_text()
        for scenario, count in ((0, 1), (6, 3), (8, 3), (9, 1)):
            attempts = re.findall(r'ORDER_SEEN id=' + controlled[scenario] + r' attempt=(\d+)', text)
            assert attempts == list(map(str, range(1, count + 1))), (scenario, attempts)
        assert 'PRIVATE_PAYLOAD_' + run not in text, 'Malformed input must not leak into logs'
        stop(consumer)
        initial_rows = export()
        for position in (positions[8], positions[9], malformed):
            assert record_id(position) in initial_rows, 'Recovery must persist real SQL rows'
        print('Retry classification, malformed JSON and persistent DLQ metadata: passed', flush=True)

        consumer, failed_log = start('consumer', 'orders', 'readonly-consumer', group,
                                    '--spring.datasource.url=jdbc:h2:file:./target/failures;ACCESS_MODE_DATA=r;IFEXISTS=TRUE',
                                    '--spring.jpa.hibernate.ddl-auto=validate')
        ready(group, 5)
        failed_key = order(9)
        failed = inject_order(failed_key)
        wait_log(failed_log, f'DLQ_FAILED topic=orders partition={failed[0]} offset={failed[1]} ')
        assert offsets(group, 'orders').get(failed[0], -1) <= failed[1], 'Failed DLQ must not advance the committed offset'
        stop(consumer)
        assert record_id(failed) not in export(), 'Read-only database must not accept recovery'
        consumer, recovered_log = start('consumer', 'orders', 'recovered-consumer', group)
        ready(group, 5)
        wait_log(recovered_log, f'DLQ_STORED topic=orders partition={failed[0]} offset={failed[1]} ')
        await_state(lambda: offsets(group, 'orders'), lambda rows: rows.get(failed[0], -1) > failed[1])
        stop(consumer)
        recovered = export()
        assert record_id(failed) in recovered
        print('SQL failure blocks offset commit; restart recovers the same record: passed', flush=True)

        # Синтетический replay offsets проверяет идемпотентность; это не имитация атомарной транзакции Kafka + SQL.
        broker_cli('kafka-consumer-groups.sh', '--group', group, '--topic', f'orders:{failed[0]}',
                   '--reset-offsets', '--to-offset', str(failed[1]), '--execute')
        consumer, replay_log = start('consumer', 'orders', 'replay-consumer', group)
        ready(group, 5)
        wait_log(replay_log, f'DLQ_STORED topic=orders partition={failed[0]} offset={failed[1]} ')
        await_state(lambda: offsets(group, 'orders'), lambda rows: rows.get(failed[0], -1) > failed[1])
        stop(consumer)
        replayed = export()
        assert recovered.keys() == replayed.keys(), 'Replay must not create another SQL row'
        for row in replayed.values():
            assert 'PRIVATE_PAYLOAD_' not in str(row), 'DLQ stores metadata and a safe category only'
        (output / 'results.json').write_text(json.dumps({'uuid_delivered': len(expected), 'orders_delivered': len(random_records),
            'attempts': {'success': 1, 'transient': 3, 'exhausted': 3, 'invalid': 1},
            'sql_failure_position': failed, 'dlq_rows_after_replay': len(replayed)}, indent=2))
        print('Replaying a recovered offset keeps one SQL row: passed', flush=True)
        print(output, flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for handle in handles:
            handle.close()


if __name__ == '__main__':
    main()
