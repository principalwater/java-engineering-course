#!/bin/sh
set -eu
STOREFRONT_WRITER_PASSWORD=$(cat /run/secrets/analytics_writer_password)
STOREFRONT_READER_PASSWORD=$(cat /run/secrets/analytics_reader_password)
: "${STOREFRONT_WRITER_PASSWORD:?Writer password file is empty}"
: "${STOREFRONT_READER_PASSWORD:?Reader password file is empty}"
# from_env не включает пароли в XML-шаблон, аргументы процесса или образ.
export STOREFRONT_WRITER_PASSWORD STOREFRONT_READER_PASSWORD
STOREFRONT_CLUSTER_SECRET=$(cat /run/secrets/analytics_cluster_secret)
: "${STOREFRONT_CLUSTER_SECRET:?Cluster secret file is empty}"
export STOREFRONT_CLUSTER_SECRET
exec /entrypoint.sh "$@"
