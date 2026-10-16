#!/bin/sh
set -eu
: "${SESSION_RUNTIME_USER:?Session runtime UID:GID is required}"
# Инициализация нового тома требует root; сам Redis работает с UID владельца read-only secrets.
chown "$SESSION_RUNTIME_USER" /data
exec gosu "$SESSION_RUNTIME_USER" redis-server /etc/redis/sessions.conf
