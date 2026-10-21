#!/bin/bash
# Учётные данные Kibana и ключи шифрования сохранённых объектов читаются из смонтированных Secret
# перед запуском Kibana. В kibana.yml секретов нет.
set -euo pipefail
umask 077
read_secret() {
    if [[ ! -s "/run/secrets/$1" ]]; then
        echo "Secret file $1 is missing or empty" >&2
        exit 1
    fi
    tr -d '\r\n' < "/run/secrets/$1"
}
ELASTICSEARCH_PASSWORD="$(read_secret kibana_system_password)"
master="$(read_secret kibana_encryption_key)"
if (( ${#master} < 32 )); then
    echo "Secret file kibana_encryption_key must hold at least 32 characters" >&2
    exit 1
fi
# Из одного внешнего ключа выводятся три ключа для разных целей: утечка одного не раскрывает остальные.
derive() { printf '%s:%s' "$1" "$master" | sha256sum | cut -d' ' -f1; }
XPACK_SECURITY_ENCRYPTIONKEY="$(derive security)"
XPACK_ENCRYPTEDSAVEDOBJECTS_ENCRYPTIONKEY="$(derive savedobjects)"
XPACK_REPORTING_ENCRYPTIONKEY="$(derive reporting)"
export ELASTICSEARCH_PASSWORD XPACK_SECURITY_ENCRYPTIONKEY XPACK_ENCRYPTEDSAVEDOBJECTS_ENCRYPTIONKEY XPACK_REPORTING_ENCRYPTIONKEY
unset master
exec /usr/local/bin/kibana-docker "$@"
