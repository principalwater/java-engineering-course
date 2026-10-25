#!/bin/sh
# Собирает параметры HTTPS-слушателя при старте. Winstone принимает только keystore и берёт пароль из командной
# строки, поэтому пароль читается здесь из смонтированного секрета, а не хранится в конфигурации или окружении контейнера.
set -eu

secrets=/run/bank-jenkins
if [ ! -s "$secrets/keystore_password" ] || [ ! -s "$secrets/tls_keystore.p12" ]; then
    echo 'The HTTPS keystore or its password is missing from the controller secret mount' >&2
    exit 1
fi

JENKINS_OPTS="--httpPort=-1 --httpsPort=${BANK_JENKINS_HTTPS_PORT:-8443} --httpsKeyStore=$secrets/tls_keystore.p12"
JENKINS_OPTS="$JENKINS_OPTS --httpsKeyStoreType=PKCS12 --httpsKeyStorePassword=$(cat "$secrets/keystore_password")"
export JENKINS_OPTS
exec /usr/local/bin/jenkins.sh "$@"
