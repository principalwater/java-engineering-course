# Sprint 10: OpenTofu и Helm

Независимый root использует OpenTofu 1.11.x, провайдеры Helm/Kubernetes 3.3.0 и внешний local state для учебного запуска одним пользователем. Он не меняет Docker-root `infra/` и состояния предыдущих спринтов. Для совместной production-работы требуется отдельный удалённый backend с блокировкой и шифрованием.

Два native `helm_release`: официальный Strimzi 1.3.0 из OCI по digest и собственный umbrella chart банка с Kafka subchart. Namespace `bank10-dev`, `bank10-test` или `bank10-prod` и context задаются явно. Kind-кластеры `bank10-<environment>-single` и `bank10-<environment>-ha` имеют раздельные kubeconfig, сети, PVC и state; смена профиля поверх старого KRaft-хранилища не поддерживается.

Single содержит один combined broker/controller. HA содержит три выделенных controller и три broker на трёх kind-нодах; RF3/minISR2. Control plane также выполняет рабочую роль, остальные две ноды — worker: это снижает накладные расходы локальной VM без уменьшения лимитов сервисов. Проверка останавливает worker, сохраняя Kubernetes API. Это проверка отказа процессов/worker nodes внутри одной VM. PostgreSQL, ZooKeeper, Keycloak и приложения остаются одиночными: полной production HA у всего банка нет. Для промышленного control plane нужен отдельный расчёт ресурсов. При 12GiB Docker VM сценарии single с analytics/Superset и HA core запускаются последовательно; их фактическую память нужно проверять перед дополнительной нагрузкой.

Kafka предоставляет только внутренний TLS/SCRAM-SHA-512 listener. Каждый сервис получает свой KafkaUser Secret и literal ACL темы/группы; CA монтируется в PEM, hostname verification включена. Source/DLT имеют по шесть partitions, DLT хранится 30 дней, бизнес-темы — семь. Автосоздание тем отключено. В default kind CNI не доказано исполнение NetworkPolicy: сетевую изоляцию политиками этот стенд не заявляет.

Пароли OAuth/БД и TLS-файлы загружаются bootstrap по stdin из внешнего AES-каталога. Kubeconfig и ключ шифрования etcd также остаются там. Helm/OpenTofu содержат только Secret references; `sensitive=true` не используется как замена этому ограничению. На macOS hostPath PVC находятся на FileVault; Linux private-file режим разрешён только в изолированной CI. Для production нужен шифрованный StorageClass и внешний secret manager. Routine stop сохраняет claims, exact node/network identities и state, останавливая приложения перед хранилищами; `destroy`, удаление PVC и глобальная очистка не используются.

Обновление приложений требует maintenance window: Deployment имеет ровно одну replica и стратегию `Recreate`. Старый writer завершается до запуска нового Pod и его Flyway; HPA и rolling overlap не предусмотрены. Это ограничение необходимо сохранять при миграции старого REST outbox, а не объявлять такую миграцию zero-downtime.

Локальный single с analytics/Superset проверен на VM 12GiB: 66 объектов chart прошли server-side admission, четыре initializer Job завершились, повторный plan содержит два `no-op`. TLS/SCRAM, hostname verification и запрет чужих записей подтверждены для пяти Kafka principals; чужие consumer groups запрещены трём consumer principals. Запись пережила перезапуск broker, идентификаторы семи PVC сохранились. Проверены шифрование Secret в etcd и отсутствие точных secret values в plan/state/deployment logs. HTTPS/OIDC, денежные API, доставка/прочтение уведомлений, шесть реальных BI charts и независимое восстановление Cassandra/ClickHouse прошли runtime-проверку.

## Проверки без запуска приложений

```sh
python3 -m venv /tmp/bank10-infra-venv
/tmp/bank10-infra-venv/bin/python -m pip install -r infra/requirements.txt
/tmp/bank10-infra-venv/bin/python -m unittest discover -s infra/kubernetes/tests -p 'test_*.py'
tofu -chdir=infra/kubernetes init -backend=false
tofu -chdir=infra/kubernetes fmt -check -recursive
tofu -chdir=infra/kubernetes validate
tofu -chdir=infra/kubernetes test
```

Mock-тесты root автономны: минимальный fixture chart не требует наличия учебного проекта в отдельном инфраструктурном PR. Рендер и runtime-проверки проекта выполняются после появления `projects/sprint10-kafka`; тесты chart запускаются через `/tmp/bank10-infra-venv/bin/python -m unittest discover -s projects/sprint10-kafka/deployment/kubernetes -p 'test_*.py'`.

## Локальный запуск

Сначала собрать актуальные JAR через проектный Gradle `check packageDeployment`, подготовить AES source с TLS SAN `bank.valgrind-home.localhost`, `auth-bank.valgrind-home.localhost`, `insights.valgrind-home.localhost`, `superset`. Ни один helper не запускает Gradle за пользователя. Пример для single с BI; `STATE` и `SECRETS` должны быть абсолютными внешними путями:

```sh
STATE="$HOME/Documents/study/java-runtime/sprint10-bank-single"
SECRETS="$HOME/.config/java-engineering-course/sprint10"
PROJECT="$PWD/projects/sprint10-kafka"
PY=/tmp/bank10-infra-venv/bin/python
$PY "$PROJECT/deployment/kubernetes/prepare.py" --output-dir "$STATE" --profile single --superset
$PY "$PROJECT/deployment/kubernetes/runtime.py" --state-dir "$STATE" create --profile single --secrets-dir "$SECRETS"
$PY "$PROJECT/deployment/kubernetes/runtime.py" --state-dir "$STATE" secrets --secrets-dir "$SECRETS"
$PY "$PROJECT/deployment/kubernetes/runtime.py" --state-dir "$STATE" load-images
$PY infra/kubernetes/deploy.py plan --project-dir "$PROJECT" --state-dir "$STATE" --secrets-dir "$SECRETS"
# Проверить JSON/receipt: apply использует именно сохранённый проверенный бинарный plan.
$PY infra/kubernetes/deploy.py apply --project-dir "$PROJECT" --state-dir "$STATE" --secrets-dir "$SECRETS"
$PY "$PROJECT/deployment/kubernetes/runtime.py" --state-dir "$STATE" ready
$PY "$PROJECT/deployment/kubernetes/probe.py" --state-dir "$STATE" security
$PY "$PROJECT/deployment/kubernetes/probe.py" --state-dir "$STATE" persistence
$PY infra/kubernetes/deploy.py noop --project-dir "$PROJECT" --state-dir "$STATE" --secrets-dir "$SECRETS"
$PY "$PROJECT/deployment/kubernetes/runtime.py" --state-dir "$STATE" stop
BANK_SECRETS_DIR="$SECRETS" $PY "$PROJECT/deployment/scripts/init-secrets.py" --lock
```

HA core: отдельный `STATE=.../sprint10-bank-test-ha`, `prepare --environment test --profile ha` без optional flags и `create --environment test --profile ha`. Перед resume разблокировать тот же AES source, затем `runtime.py --state-dir ... resume`. Helpers проверяют cluster UID, exact node/network identities и namespace ownership, сохраняют completed Jobs и восстанавливают исходный cordon state без изменения replica counts. Старт и штатная остановка чужих kind-кластеров не выполняются.

`deploy.py` проверяет source/JAR/chart/provider-lock hashes, блокирует чужие ресурсы, destroy/replacement и изменившийся saved plan; exact secret-value scan проверяет public values, plan/state и redacted logs. Для bootstrap CRD до готовности JAR допустим отдельный reviewed `plan/apply --operator-only`; этот план не включает банк. Kafka probe использует собственные краткоживущие topic/user/job с exact identity, не меняет бизнес-данные и удаляет только эти test resources. HA proof временно оставляет два broker и два controller, проверяет acknowledged запись и чтение после восстановления, сохраняя PVC UID.
