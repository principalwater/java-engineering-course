FROM apache/superset:6.1.0@sha256:16b50bbef6648912a79e3293d418fefa743ce555d36c5af6b85d859119ed7f88
USER root
RUN uv pip install --python /app/.venv/bin/python --no-cache Authlib==1.9.1 psycopg2-binary==2.9.13 clickhouse-connect==1.11.0
COPY --chown=superset:superset superset_config.py initialize.py start.sh /app/bank/
ENV SUPERSET_CONFIG_PATH=/app/bank/superset_config.py
USER superset
ENTRYPOINT ["/bin/bash", "/app/bank/start.sh"]
