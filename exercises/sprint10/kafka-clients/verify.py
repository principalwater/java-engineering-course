#!/usr/bin/env python3
"""Живые проверки batching, rebalance, crash replay и graceful offset commit."""
import json
import math
import os
from pathlib import Path
import re
import signal
import statistics
import sys
import subprocess
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parent
CLI = ROOT / 'build/install/kafka-clients/bin/kafka-clients'
sys.path.insert(0, str(ROOT.parent))
from kafka_fixture import await_state, members, offsets

RECORD = re.compile(r'RECORD client=(\S+) key=(\S+) partition=(\d+) offset=(\d+) delay_ms=(-?\d+) value=(.*)')


def records(path, prefix):
    return {(int(m[3]), int(m[4])): {'client': m[1], 'key': m[2], 'delay_ms': int(m[5]), 'value': m[6]}
            for line in path.read_text().splitlines() if (m := RECORD.fullmatch(line)) and m[6].startswith(prefix)}


def main():
    run = uuid4().hex[:10]
    output = ROOT / 'build/verification' / run
    output.mkdir(parents=True)
    processes, handles = [], []
    env = {**os.environ, 'JAVA_OPTS': '-Xmx160m -Dorg.slf4j.simpleLogger.defaultLogLevel=warn'}
    def consumer(name, group):
        path = output / (name + '.log')
        handle = path.open('w')
        handles.append(handle)
        process = subprocess.Popen([str(CLI), 'consumer', name, '3', '180', group], cwd=ROOT, env=env,
                                   stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        return process, path
    def produce(prefix, mode='normal', count=10):
        path = output / (prefix + '.log')
        with path.open('w') as log:
            subprocess.run([str(CLI), 'producer', prefix, '3', str(count), '100', mode], cwd=ROOT, env=env,
                           stdout=log, stderr=subprocess.STDOUT, timeout=45, check=True)
        sent = {(int(m[1]), int(m[2])) for line in path.read_text().splitlines()
                if (m := re.fullmatch(r'SENT client=\S+ key=\S+ partition=(\d+) offset=(\d+)', line))}
        assert len(sent) == 3 * count, 'Every send needs a broker acknowledgement'
        return sent
    try:
        group = 'mystery-consumers-close-' + run
        graceful, log = consumer('close-' + run, group)
        await_state(lambda: members(group), lambda rows: len(rows) == 3 and sum(n for _, n in rows) == 5)
        prefix = 'close-record-' + run
        expected = produce(prefix, count=3)
        seen = await_state(lambda: records(log, prefix), lambda rows: expected <= rows.keys())
        before = offsets(group)
        assert any(before.get(p, -1) <= o for p, o in seen), 'Close proof needs uncommitted input'
        graceful.terminate()
        graceful.wait(timeout=20)
        after = offsets(group)
        assert all(after.get(p, -1) > o for p, o in seen), 'SIGTERM must wait for owner-thread close and offset commit'
        print('Graceful SIGTERM committed all processed offsets before JVM exit: passed', flush=True)

        group = 'mystery-consumers-' + run
        first, first_log = consumer('first-' + run, group)
        await_state(lambda: members(group), lambda rows: len(rows) == 3 and sum(n for _, n in rows) == 5)
        measurements, key_partitions = {}, {}
        for mode in ('normal', 'batch'):
            prefix = mode + '-' + run
            expected = produce(prefix, mode, count=40)
            seen = await_state(lambda: records(first_log, prefix), lambda rows: expected <= rows.keys())
            for (partition, _), record in seen.items():
                assert key_partitions.setdefault(record['key'], partition) == partition
            delays = sorted(v['delay_ms'] for v in seen.values())
            measurements[mode] = {'records': len(delays), 'mean_ms': statistics.mean(delays),
                                  'p50_ms': statistics.median(delays), 'p95_ms': delays[math.ceil(len(delays) * .95) - 1]}
        second, second_log = consumer('second-' + run, group)
        assignment = await_state(lambda: members(group), lambda rows: len(rows) == 6
                                 and sorted(n for _, n in rows) == [0, 1, 1, 1, 1, 1])
        print('Six consumers share five partitions; one has no assignment: passed', flush=True)
        prefix = 'crash-' + run
        produce(prefix, count=15)
        seen = await_state(lambda: records(first_log, prefix), bool)
        first.send_signal(signal.SIGSTOP)
        current = offsets(group)
        candidates = {key: value for key, value in seen.items() if current.get(key[0], -1) <= key[1]}
        assert candidates, 'Crash proof needs broker-confirmed uncommitted records'
        assert not candidates.keys() & records(second_log, prefix).keys()
        first.kill()
        first.wait(timeout=10)
        await_state(lambda: members(group), lambda rows: len(rows) == 3 and sum(n for _, n in rows) == 5)
        replayed = await_state(lambda: records(second_log, prefix), lambda rows: bool(rows.keys() & candidates.keys()))
        repeated = next(iter(replayed.keys() & candidates.keys()))
        assert replayed[repeated]['key'] == candidates[repeated]['key']
        assert replayed[repeated]['value'] == candidates[repeated]['value']
        print('Crash replay of a proven uncommitted partition/offset: passed', flush=True)
        (output / 'results.json').write_text(json.dumps({'delivery': measurements, 'key_partitions': key_partitions, 'six_members': assignment,
                                                       'replayed_partition_offset': list(repeated)}, indent=2))
        print(json.dumps(measurements), flush=True)
    finally:
        for process in processes:
            if process.poll() is None:
                process.send_signal(signal.SIGCONT)
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for handle in handles:
            handle.close()


if __name__ == '__main__':
    main()
