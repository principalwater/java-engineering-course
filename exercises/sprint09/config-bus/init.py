#!/usr/bin/env python3
"""Готовит локальные секреты; повторный запуск сохраняет существующие значения."""
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parent
path = ROOT / 'target/lab.env'
path.parent.mkdir(exist_ok=True)
keys = ('LAB_OPERATOR_PASSWORD', 'RABBITMQ_DEFAULT_PASS')
if not path.exists():
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
        file.write(''.join(f'{key}={secrets.token_hex(32)}\n' for key in keys))
values = dict(line.split('=', 1) for line in path.read_text().splitlines())
if set(values) != set(keys) or any(len(value) != 64 or any(c not in '0123456789abcdef' for c in value) for value in values.values()):
    raise SystemExit('Invalid local secret file; preserve it and inspect its format')
path.chmod(0o600)
rabbit = path.parent / 'rabbit.env'
with os.fdopen(os.open(rabbit, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
    output.write('RABBITMQ_DEFAULT_PASS=' + values['RABBITMQ_DEFAULT_PASS'] + '\n')
rabbit.chmod(0o600)
config = ROOT / 'target/config-repo/property-printer-default.yml'
config.parent.mkdir(exist_ok=True)
if not config.exists():
    config.write_text('text-to-print: "Текст до изменений"\n')
print('Local secrets and configuration ready; secret values are not printed')
