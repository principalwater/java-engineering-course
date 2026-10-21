#!/usr/bin/env bash
set -euo pipefail
umask 077
export PYTHONPATH="/app/bank${PYTHONPATH:+:$PYTHONPATH}"
superset db upgrade
superset init
/app/.venv/bin/python /app/bank/initialize.py
# Два синхронных worker покрывают небольшой BI-стенд без отдельной очереди Celery.
# Linux dual-stack socket принимает обе семьи: Java выбирает AAAA сервисного DNS.
exec /app/.venv/bin/gunicorn --bind '[::]:8088' --certfile /run/secrets/tls_certificate.pem --keyfile /run/secrets/tls_private_key.pem --workers 2 --threads 2 --timeout 30 --limit-request-line 8190 'superset.app:create_app()'
