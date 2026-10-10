#!/bin/sh
set -eu
PG_READER_PASSWORD=$(cat "${PG_READER_PASSWORD_FILE:?}")
: "${PG_READER_PASSWORD:?Reader password file is empty}"
# Named collection использует from_env; пароль остаётся внутри процесса контейнера.
export PG_READER_PASSWORD
exec /entrypoint.sh "$@"
