#!/bin/bash
set -euo pipefail
# Штатный entrypoint меняет конфигурацию; его writable-копия остаётся только в tmpfs.
mkdir -p /tmp/bank-cassandra
cp -a /etc/cassandra/. /tmp/bank-cassandra/
sed -i 's/^authenticator:.*/authenticator: PasswordAuthenticator/; s/^authorizer:.*/authorizer: CassandraAuthorizer/' /tmp/bank-cassandra/cassandra.yaml
# Бюджет одного учебного узла: встроенные кеши по умолчанию рассчитаны на сервер с большей RAM.
sed -i -E \
    -e 's/^#? ?key_cache_size:.*/key_cache_size: 16MiB/' \
    -e 's/^#? ?counter_cache_size:.*/counter_cache_size: 8MiB/' \
    -e 's/^#? ?networking_cache_size:.*/networking_cache_size: 16MiB/' \
    -e 's/^#? ?file_cache_size:.*/file_cache_size: 32MiB/' \
    -e 's/^#? ?memtable_heap_space:.*/memtable_heap_space: 64MiB/' \
    -e 's/^#? ?memtable_offheap_space:.*/memtable_offheap_space: 32MiB/' \
    -e 's/^#? ?index_summary_capacity:.*/index_summary_capacity: 8MiB/' \
    -e 's/^#? ?native_transport_max_threads:.*/native_transport_max_threads: 16/' \
    -e 's/^#? ?concurrent_reads:.*/concurrent_reads: 4/' \
    -e 's/^#? ?concurrent_writes:.*/concurrent_writes: 4/' \
    -e 's/^#? ?concurrent_counter_writes:.*/concurrent_counter_writes: 2/' \
    -e 's/^#? ?concurrent_compactors:.*/concurrent_compactors: 1/' /tmp/bank-cassandra/cassandra.yaml
chown -R cassandra:cassandra /tmp/bank-cassandra
export CASSANDRA_CONF=/tmp/bank-cassandra
exec /usr/local/bin/docker-entrypoint.sh "$@"
