# OpenBao: хранилище runtime-секретов Sprint 11

Один сервер OpenBao 2.7.1 (Helm-чарт `openbao` 0.30.4) с постоянным Raft-хранилищем в одной реплике: данные переживают
перезапуск, но высокой доступности нет. Все runtime-секреты приложений и платформы читаются из OpenBao; Java-сервисы
берут свойства через Spring Cloud Vault 4.3.3, остальные файлы синхронизирует External Secrets Operator 2.13.
Внешние исключения bootstrap-а: TLS-ключ сервера (Kubernetes Secret `openbao-tls`) и recovery-файл с ключом unseal и
root-токеном (только во внешнем зашифрованном источнике AES, не в кластере, Terraform, state и логах).

Ресурсы: `values.yaml` (чарт и образ закреплены digest-ами, TLS обязателен, dev-режима нет),
`deployment/kubernetes/bao.py` (клиент и жизненный цикл), `deployment/kubernetes/test_bao.py` (проверки без кластера).

## Порядок и команды

`python bao.py --state-dir S <команда> --secrets-dir D`; кластер должен быть создан `runtime.py`, `S` содержит
`cluster.json` и `secret-bindings.json` (результат `prepare.py`).

| Команда | Действие (идемпотентно, секреты не печатаются) |
|---|---|
| `tls` | создаёт namespace `bank11-system` (метка владельца) и Secret `openbao-tls` (`tls.crt`, `tls.key`, `ca.crt`); изменённый материал не заменяет |
| - | OpenTofu: `helm_release` `openbao` в `bank11-system`, `wait = false`, `values = [file(deployment/openbao/values.yaml)]`, чарт `oci://ghcr.io/openbao/charts/openbao@sha256:7d0ac0321c10c70d4fda3529416a33abb037d4cca01ab70c8acdf9a02b12adb8` |
| `bootstrap` | init (1 доля Shamir) один раз, recovery сохраняется до unseal, затем unseal и ожидание active |
| `unseal` | только unseal после `runtime.py resume`/рестарта пода; init не выполняет |
| `configure` | требует HMAC-аудит; монтирует KV v2 `kv/` и auth `kubernetes/`, пишет политики и роли |
| `import` | KV по `secret-bindings.json` из внешних файлов; создаёт путь (`cas=0`), добавляет недостающие ключи, отличающееся значение не заменяет (ротация только явная) |
| `verify [--exercise-seal]` | живые проверки (ниже); с флагом запечатывает сервер и всегда распечатывает его обратно |
| `snapshot` | Raft-снимок рядом с recovery; без ключа unseal бесполезен |

Reset, adoption, ротации и восстановление из снимка не автоматизированы: команда отказывает, если recovery есть при
неинициализированном сервере, если сервер инициализирован без recovery, если mount или Secret уже существуют иначе.

## Внешние файлы (`--secrets-dir`, каталог 0700, файлы 0600)

- Читает: `internal_ca.pem`, `internal_openbao_certificate.pem`, `internal_openbao_key.pem` (`init-internal-tls.py`; SAN
  сертификата обязан содержать `openbao.bank11-system.svc.cluster.local`) и исходные файлы из `secret-bindings.json`.
- Создаёт: `openbao/<кластер>/recovery.json` (`unseal_key`, `root_token`), `openbao/<кластер>/snapshots/raft-*.snap`.
- Корневой токен остаётся в recovery (не отзывается); в Kubernetes он не попадает. Ключ CA в кластер не передаётся.

## Транспорт

Клиент идёт через `kubectl port-forward pod/openbao-0` (туннель TCP, TLS не терминируется) и проверяет сертификат
по `internal_ca.pem` для имени `openbao.bank11-system.svc.cluster.local` (strict X.509); обхода проверки нет. Перед
отправкой токена проверяются владелец namespace, метки пода и закреплённый образ из `values.yaml`. Тело ответа API не
сохраняется и не попадает в ошибки.

## Пути, политики и роли

Значение `bank11-<имя>` из `secret-bindings.json` хранится в `kv/<namespace>/<имя>` (ключ KV = целевое имя файла или
свойство). Однострочные значения сохраняются без завершающего перевода строки, PEM-файлы - без изменений.
Gateway получает отдельную роль для собственного TLS-ключа; OAuth2/БД-секреты ему не выдаются.

| Кто | Роль и политика | Доступ |
|---|---|---|
| Java-сервис из `prepare.APPS` | `<namespace>-<сервис>`, ServiceAccount `<сервис>` | `read` на `kv/data/<namespace>/<сервис>` |
| остальные привязки (`database`, `edge`, `superset` и т. д.) | `<namespace>-eso`, ServiceAccount `eso` | `read` на точные пути этих привязок и `sys/mounts/kv` (только параметры mount) |

Роль привязана к ServiceAccount и namespace, `audience = openbao`, токены `service`, продление разрешено,
`token_ttl` 15 минут, `token_max_ttl` 1 час. Подстановок, `list` и чтения KV metadata в политиках нет. Роли и политики задаются `configure`; `verify` считает любое расхождение ролей и политик ошибкой.

## Интеграция приложений и платформы

- ServiceAccount `<сервис>` и `eso` создаёт чарт в namespace окружения (`bank11-dev` и т. д.).
- Spring Cloud Vault: `spring.cloud.vault.uri=https://openbao.bank11-system.svc.cluster.local:8200`,
  `authentication=KUBERNETES`, `kubernetes.role=<namespace>-<сервис>`, `kubernetes.service-account-token-file` -
  projected-токен с `audience: openbao`, `kv.backend=kv`, trust store с `internal_ca.pem`,
  `spring.config.import=vault:///kv/<namespace>/<сервис>` (контекстное расположение: стандартное `vault://` читает ещё
  `<сервис>/<профиль>`, которые политика запрещает).
- ESO: namespace-scoped `SecretStore` с `provider.openBao`: `server` как выше, `path: kv`, `version: v2`,
  `caProvider` с `ca.crt` из публичного ConfigMap; `auth.kubernetes.path: kubernetes`,
  `role: <namespace>-eso`, `serviceAccountRef: {name: eso, audiences: [openbao]}`.
  `ExternalSecret.remoteRef.key = <namespace>/<имя>`, `property` - конкретное поле; `deletionPolicy: Retain`
  сохраняет последнюю подтверждённую копию при недоступности OpenBao.
- Strimzi получает SCRAM-пароли из синхронизированных ESO Secrets через `KafkaUser.authentication.password.valueFrom`.
  Оператор самостоятельно выпускает CA/сертификаты Kafka: это производные идентичности кластера, как и короткие
  Kubernetes ServiceAccount-токены. Они не импортируются обратно в KV и не требуют роли записи.
- Сетевой доступ Java к OpenBao разрешён явно через NetworkPolicy/Cilium. Java не монтирует парольные Secret-volume;
  Spring получает свойства при старте и поддерживает lifecycle токена. Уже загруженный статический пароль не
  отзывается вместе с Vault-токеном: для смены пароля нужна согласованная ротация целевой БД и перезапуск клиента.
- OpenTofu хранит публичные конфигурации и ссылки на имена Secret, а не их значения. Bootstrap TLS, root/recovery и
  исходные файлы для восстановления остаются во внешнем зашифрованном AES-томе на macOS. Изолированный Linux CI
  генерирует собственные временные тестовые секреты; реальные токены уведомлений туда не передаются.

## Аудит и ограничения

Устройство `file` объявлено в конфигурации сервера (`log_raw = false`: строки хэшируются HMAC-SHA256) и пишет в
отдельный том 256 MiB; sidecar ротирует файл при 16 MiB и хранит до пяти файлов, после чего сигнал SIGHUP
переоткрывает файл. Проверка раз в минуту: резкий рост может временно превысить лимит, поэтому резкий всплеск требует контроля свободного места и доставки аудита во внешнее хранилище.
Недоступный журнал останавливает обработку запросов, поэтому `configure` начинает с проверки устройства.
Готовность пода (readiness) означает «инициализирован, распечатан, active»; kubelet не проверяет сертификат, проба
не передаёт данных.

## Проверки

`verify`: роли, политики и аудит совпадают с планом; для каждой роли определение KV v2 через внутренний mount endpoint, чтение собственных путей, отказ на чужих,
точные capabilities (`read` на собственном пути, `deny` на metadata и потомках), отказ входа при чужом ServiceAccount,
audience по умолчанию и одноимённом ServiceAccount в другом namespace, продление, отзыв и отказ после отзыва;
с `--exercise-seal` запечатанный сервер ничего не отдаёт. Временные ServiceAccount в `bank11-system` удаляются по метке
запуска. `test_bao.py` проверяет клиента на реальном TLS с сертификатами `init-internal-tls.py`; поведение настоящего
OpenBao и kubectl проверяет только запуск на кластере.
