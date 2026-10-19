# Банк: Apache Kafka

Продолжение банка спринта 9. Денежные команды сохраняют REST-контракты и атомарную запись в Accounts; уведомления переходят на Kafka. Notifications работает без HTTP-сервера, а кабинет читает отдельную проекцию уведомлений в Accounts.

[Структура](#структура) · [Архитектура](#архитектура) · [Kafka](#доставка-событий) · [Кабинет](#кабинет) · [Аналитика](#аналитика) · [Деплой](#деплой) · [Проверки](#проверки) · [Ограничения](#границы-стенда)

## Структура

```text
sprint10-kafka/
├── services/                 # восемь приложений, включая Java-инициализатор BI
│   ├── accounts/             # профиль, баланс, история и проекция уведомлений
│   ├── cash/, transfer/      # денежные команды
│   ├── notifications/        # консьюмер Kafka без HTTP-сервера
│   ├── front/ui/, gateway/   # кабинет Astro/D3 и API Gateway
│   └── analytics/, bank-bi/  # аналитические проекции и настройка Superset
├── libraries/                # общие Java-модули и тестовая поддержка
│   ├── bank-contracts/, bank-runtime/, bank-service-client/
│   └── bank-messaging/, bank-test-support/
├── deployment/               # исходные файлы деплоя, отдельно от Java-кода
│   ├── charts/bank/          # Helm, включая Kafka и Superset
│   ├── kubernetes/           # подготовка, управление kind и проверки Kafka
│   ├── scripts/              # секреты, TLS, запуск зависимостей и HTTP-проверки
│   └── authorization/, database/, discovery/, edge/, superset/
├── config/                   # правила PMD и настройки JVM
├── gradle/, gradlew          # единая сборка Gradle
└── Dockerfile                # общий образ Java-приложений
```

Имена Gradle-модулей остаются плоскими: `:accounts`, `:bank-runtime`, `:front` и остальные. Их каталоги задаёт `settings.gradle`; зависимости, команды сборки и координаты контрактных артефактов не меняются. `packageDeployment` собирает готовые JAR в `build/deployment/` — это результат сборки, а не исходный каталог `deployment/`.

Ориентировался на разделение модулей и вспомогательных инструментов в [Spring Boot](https://github.com/spring-projects/spring-boot) и [Elasticsearch](https://github.com/elastic/elasticsearch). Общая конфигурация OpenTofu остаётся в [infra/kubernetes/](../../infra/kubernetes/), чтобы не дублировать её внутри проекта.

## Архитектура

```mermaid
flowchart LR
    Browser[Кабинет Astro / D3] --> Front
    Front --> Gateway
    Gateway --> Accounts
    Gateway --> Cash
    Gateway --> Transfer
    Cash -->|REST / Client Credentials| Accounts
    Transfer -->|REST / Client Credentials| Accounts
    Accounts --> Outbox[Транзакционный outbox]
    Cash --> Outbox
    Transfer --> Outbox
    Outbox --> Kafka
    Kafka --> Notifications
    Notifications --> Receipts[Сохранённые уведомления / outbox]
    Receipts --> Kafka
    Kafka --> Inbox[Проекция уведомлений Accounts]
    Accounts --> Inbox
    Kafka --> Analytics
    Analytics --> Cassandra
    Analytics --> ClickHouse
    Cassandra -->|Java / версии снимков| ClickHouse
    ClickHouse --> Superset
```

| Модуль | Ответственность |
| --- | --- |
| [accounts](services/accounts/) | Профиль, баланс, история, атомарный перевод, собственный список уведомлений и отметка прочтения |
| [cash](services/cash/), [transfer](services/transfer/) | Идемпотентные денежные команды, восстановление неизвестного результата, уведомления через outbox |
| [notifications](services/notifications/) | Консьюмер Kafka, неизменяемая квитанция, Kafka-публикация результата, ограниченное восстановление проекции |
| [front](services/front/), [gateway](services/gateway/) | Authorization Code + PKCE, CSRF, серверная сессия, Token Relay и маршрутизация |
| [bank-contracts](libraries/bank-contracts/) | Общие типы команд, событий и ответы API |
| [bank-messaging](libraries/bank-messaging/) | Невебовые Kafka/JDBC-механизмы и ограниченная доставка |
| [bank-runtime](libraries/bank-runtime/), [bank-service-client](libraries/bank-service-client/) | HTTP/JWT-инфраструктура и отдельно REST-клиенты с Client Credentials |
| [bank-test-support](libraries/bank-test-support/) | Общие тестовые фикстуры PostgreSQL/JWT/Kafka и адаптер контрактных Kafka-тестов |
| [analytics](services/analytics/), [bank-bi](services/bank-bi/) | Дополнительные проекции событий и Java-инициализация дашборда Superset |

> Разделение общих модулей позволяет исключить MVC/WebFlux из Notifications, не копируя доставку и JDBC-код. OAuth-клиент подключён только там, где остались исходящие REST-вызовы; Kafka не требует получать HTTP access token перед каждой публикацией.

## Доставка событий

Accounts, Cash и Transfer публикуют в собственные топики. ACL разрешают каждому сервису запись только в его топики; Notifications определяет источник по топику, а не доверяет переданному клиентом заголовку. Партиционирование по владельцу распределяет нагрузку и сохраняет локальность событий одного счёта. Между разными топиками глобального порядка нет.

1. Изменение бизнес-данных и запись outbox происходят в одной PostgreSQL-транзакции.
2. Ограниченные воркеры публикуют неизменяемое событие. `acks=all` и идемпотентный продюсер защищают запись в Kafka; неизвестный результат отправки повторяется с прежним идентификатором.
3. Notifications сохраняет квитанцию и исходящее событие одной транзакцией. Офсет подтверждается после фиксации транзакции.
4. Accounts сохраняет собственную проекцию до подтверждения офсета консьюмера. Кабинет читает её без синхронного запроса в Notifications.

Ключ квитанции — `(source, eventId)`: одинаковый UUID у разных сервисов не считается одним уведомлением. Повтор с тем же ключом и другим владельцем или содержимым отклоняется. Консьюмер уведомлений не списывает деньги.

> `PUBLISHED` означает подтверждение брокера. Это не подтверждение появления записи в кабинете и тем более не прочтение пользователем. Первое прочтение хранится отдельно; повторная отметка не меняет его время.

Временные ошибки PostgreSQL и брокера не превращаются в потерянные финансовые события. Некорректные сообщения отправляются в DLT; исходный офсет разрешено продвинуть только после подтверждённой записи туда. Бизнес-процессы не ждут потребителя уведомлений и не выдают выдуманный баланс при неизвестном результате команды.

Бизнес-топики и их DLT имеют по шесть партиций. Срок хранения бизнес-топиков — семь дней, DLT — 30 дней; автоматическое создание топиков отключено. Группы консьюмеров фиксированы; при отсутствии офсета используется `earliest`.

### Восстановление проекции

После истечения срока хранения в Kafka можно повторно опубликовать сохранённый PostgreSQL outbox: содержимое, ключ и идентификатор события остаются прежними. В одноразовом Job с текущим образом, JDBC-конфигурацией и Secret соответствующего сервиса команда из `/app` выглядит так:

```bash
java -cp application.jar dev.principalwater.bank.messaging.replay.OutboxReplayApplication \
  --BANK_CONFIG_IMPORT=optional:classpath:bank-common.properties \
  --spring.config.import=classpath:bank-common.properties \
  --bank.outbox.replay.after=0 --bank.outbox.replay.limit=1000
```

Каждый вызов обрабатывает не более 1000 записей; следующий получает возвращённый `nextSequence`, до `count=0`. Сроки действующей аренды записей не сбрасываются. Источник ограничен именем приложения: Accounts публикует свои топики уведомлений и аналитики, Cash и Transfer — свои уведомления, Notifications — квитанции. HTTP-сервер и слушатели Kafka в этом процессе не запускаются.

Если нужно восстановить исходящие события из сохранённых квитанций Notifications, отдельный профиль того же образа повторно ставит их в исходящий outbox:

```bash
java -jar application.jar --spring.profiles.active=notification-replay \
  --bank.notification.replay.after=0 --bank.notification.replay.limit=1000
```

Сначала восстанавливается исходная цепочка, затем проекция квитанций; завершение проверяется по данным Accounts. Повтор не меняет сохранённое время прочтения. Исходные PostgreSQL-записи не очищаются автоматически; их потеря или утрата Accounts требует восстановления БД из резервной копии. Срок хранения Kafka резервную копию не заменяет.

## Кабинет

Сохранились профиль, проверка возраста, пополнение, снятие, перевод, история и разграничение прав. Дополнительно можно открыть сохранённые уведомления и отметить их прочитанными. Уведомление связано с квитанцией Notifications, а не только с ответом денежной команды.

Список и отметка прочтения проверяют владельца на сервере. Подмена `ownerId`, идентификатора уведомления или параметров браузера не выбирает чужой счёт. `GET /api/accounts/notifications` использует ограниченную пагинацию по ключу `before/limit`; новые записи не сдвигают уже открытые страницы. `POST /api/accounts/notifications/read` принимает `{source, eventId}` и требует `accounts.write`. Front проксирует их через `GET /ui/notifications` и `POST /ui/notifications/read`; изменение требует проверки CSRF.

![Квитанция Notifications и сохранённое время прочтения](../../screenshots/sprint10-kafka/01-notifications-kafka.png)

## Аналитика

> Решил дополнительно разобрать интеграцию аналитического контура с Java: от явного действия в кабинете и завершённой денежной операции до пользовательской проекции и отдельного BI-дашборда. Этот контур включается отдельно и не участвует в проведении платежа.

Есть два вида входных данных:

- **Серверные события:** завершённые денежные операции, изменение профиля, появление уведомления и первое прочтение. Владелец и финансовые значения определяются сервером.
- **События интерфейса:** фактическое открытие раздела или формы. Клиент передаёт UUID события и ограниченный тип действия; сервер добавляет владельца и время. Содержимое форм, токены и клиентские денежные суммы в эти события не попадают. Клиентские UUID преобразуются в отдельное пространство с учётом владельца и маршрута API: пользователь не может заранее занять идентификатор финансового события. Лимит просмотров и UI-действий — 1000 новых событий на владельца в месяц; идемпотентный повтор квоту не расходует.

Accounts сначала доставляет события в устойчивый inbox Analytics. Затем внутренний Kafka-поток независимо обновляет пользовательскую Cassandra-проекцию и финансовую витрину ClickHouse. Отказ одного хранилища не подтверждает доставку в другое.

### Два пути в ClickHouse

Финансовые факты Java записывает через HTTP `JSONEachRow` с `async_insert` и ожиданием подтверждения вставки. `ReplacingMergeTree` и чтение с `FINAL` позволяют повторить неизменяемое событие без повторного учёта операции.

Для витрин взаимодействий и уведомлений используется другой путь: после подтверждения Cassandra сохраняется версия снимка за месяц; Java читает две ограниченные партиции Cassandra `(owner, month)` и пакетно публикует снимок официальным ClickHouse client-v2. Снимки имеют монотонную версию; перед агрегацией выбирается актуальная версия. Это чтение конкретных партиций, без полного сканирования Cassandra.

> Оба пути используют HTTP `JSONEachRow`, но разные источники и правила подтверждения: неизменяемый финансовый факт и версионный снимок Cassandra. Пользовательская проекция и BI-витрина имеют разные задачи; дополнительный OLAP-движок поверх ClickHouse не нужен.

Личная аналитика в кабинете фильтруется по месяцу и типу операции; D3 показывает фактические поступления и списания с доступной табличной альтернативой.

![Личная аналитика по выполненным операциям](../../screenshots/sprint10-kafka/02-personal-analytics.png)

### Superset

Отдельный адрес — `https://insights.valgrind-home.localhost:8450`. Вход проходит через существующий Keycloak и разрешён роли `analyst`. Обычный клиент банка не получает доступ к внутреннему BI.

Java-модуль `bank-bi` импортирует дашборд через HTTPS API Superset со стабильными UUID, назначает права на три набора данных и выполняет запросы графиков. Повторная инициализация обновляет активы, не создавая копий. Python в образе Superset используется только для его штатной конфигурации и запуска; ETL остаётся в Java.

BI-пользователь ClickHouse читает только три месячных агрегатных представления: денежные операции, взаимодействия и уведомления. Идентификаторы владельцев в них не выдаются. Перевод считается по `TRANSFER_OUT`; зачисление получателю не удваивает число переводов или их объём. Денежный оборот не называется выручкой банка. Число активных счетов уникально внутри дня, поэтому дневные значения не суммируются в месячное число уникальных счетов.

![Денежные операции из Kafka на дашборде Superset](../../screenshots/sprint10-kafka/03-superset-financials.png)

<details>
<summary>UI-события, доставка и прочтение уведомлений</summary>

Шесть графиков и таблиц показывают реальные операции проверочного стенда. Открытие разделов приходит с фронта, изменение профиля и статусы уведомлений — с бэкенда. Для единственного дня наблюдений включены маркеры: одна точка не превращается в пустой линейный график.

![Действия в кабинете и фактические статусы уведомлений](../../screenshots/sprint10-kafka/05-superset-engagement-notifications.png)

</details>

## Деплой

Основной путь спринта — Kubernetes + Helm через отдельную корневую конфигурацию OpenTofu. Второй Compose-деплой для этого проекта не поддерживается: предыдущие спринты сохраняют свои способы запуска.

Нужны Java 21, Docker/OrbStack, Python 3, `kind`, `kubectl`, Helm, OpenTofu и локальный доверенный CA (`mkcert` на macOS). Версии образов и зависимостей закреплены в исходниках; образы приложений получают имя по содержимому собранных JAR.

Образы и кеш каждой kind-ноды занимают дополнительное место на хосте. Перед каждой сборкой и загрузкой `runtime.py` требует не менее 5 GiB свободного места. Это аварийный нижний порог, а не расчёт ёмкости всего стенда; при его достижении загрузка останавливается до очистки неиспользуемого кеша или увеличения диска.

Начальная подготовка ниже показана для macOS/Homebrew; на Linux укажите `JAVA_HOME` своего JDK 21.

```bash
cd projects/sprint10-kafka
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew --no-daemon check packageDeployment
python3 -m venv ~/.venvs/bank10-kubernetes
~/.venvs/bank10-kubernetes/bin/pip install -r deployment/kubernetes/requirements.txt
python3 deployment/scripts/init-secrets.py
python3 deployment/scripts/init-tls.py
```

Секреты на macOS находятся в AES-256 томе, пароль — в Keychain. Kubernetes получает отдельные Secret с файловыми проекциями только нужных сервису значений. Содержимое Secret не передаётся Helm/OpenTofu и не попадает в state. Etcd шифрует Secret; локальные PVC защищены шифрованием хоста. Это разные уровни защиты.

Клиенты читают секреты при запуске: после ротации нужен управляемый перезапуск соответствующих сервисов в рамках окна обслуживания. Импорт Keycloak рассчитан на новую БД; существующий realm меняется через администрирование IdP, без сброса базы авторизации.

> Для реального окружения источник секретов следует заменить внешним Vault/Secret Manager с управляемой ротацией, а локальные hostPath PVC — подходящим StorageClass и резервным копированием. Kubernetes Secret сам по себе не является внешним хранилищем секретов.

### Одна нода

```bash
export BANK_STATE="$HOME/Documents/study/java-runtime/sprint10-bank-single"
export BANK_SECRETS_DIR="$HOME/.config/java-engineering-course/sprint10"
export BANK_PYTHON="$HOME/.venvs/bank10-kubernetes/bin/python"
"$BANK_PYTHON" deployment/kubernetes/prepare.py --output-dir "$BANK_STATE" --profile single --superset
"$BANK_PYTHON" deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" create --secrets-dir "$BANK_SECRETS_DIR" --profile single
"$BANK_PYTHON" deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" secrets --secrets-dir "$BANK_SECRETS_DIR"
"$BANK_PYTHON" deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" load-images
```

Из корня репозитория:

```bash
"$BANK_PYTHON" infra/kubernetes/deploy.py plan --project-dir "$PWD/projects/sprint10-kafka" --state-dir "$BANK_STATE" --secrets-dir "$BANK_SECRETS_DIR"
# Проверить сохранённый plan и перечень действий до apply.
"$BANK_PYTHON" infra/kubernetes/deploy.py apply --project-dir "$PWD/projects/sprint10-kafka" --state-dir "$BANK_STATE" --secrets-dir "$BANK_SECRETS_DIR"
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" ready
```

Внешний порт опубликован только на loopback по TCP; Caddy использует HTTP/1.1 и HTTP/2, не рекламируя недоступный через этот проброс QUIC/HTTP/3. Внутренние Kafka-клиенты используют TLS, SCRAM и индивидуальные ACL; настройки подключения лежат в ConfigMap, пароли — в отдельных Secret.

![Нода банка в OrbStack: loopback HTTPS и отдельные постоянные тома](../../screenshots/sprint10-kafka/04-orbstack-kubernetes.png)

### Несколько нод

| Параметр | `single` | `ha` |
| --- | --- | --- |
| Kubernetes | Одна kind-нода | Три kind-ноды: узел управления с рабочей ролью и два рабочих узла |
| Kafka | Один совмещённый брокер/контроллер | Три отдельных контроллера и три брокера |
| Репликация / min ISR | `1 / 1` | `3 / 2` |
| Назначение | Полный локальный стенд, аналитика и BI | Проверка кворума и отказа одного рабочего узла |
| Kafka при отказе ноды | Сервис временно недоступен | Запись возможна при сохранении min ISR и кворума |

Deployment приложений использует одну реплику и `Recreate`: старый процесс записи завершается перед Flyway нового Pod. Для обновлений требуется окно обслуживания; параллельная работа старой и новой версии и HPA в этом стенде не поддерживаются.

Совмещение узла управления с рабочей ролью уменьшает накладные расходы локальной VM, сохраняя три хоста для разнесения Kafka-процессов. Это учебная топология; выделенная отказоустойчивая плоскость управления для промышленного окружения требует отдельного расчёта ресурсов. Проверка отказа останавливает рабочий узел, сохраняя доступ к Kubernetes API.

На снимке отдельный кластер `bank10-test-ha`: три ноды работают, single-стенд остановлен. HTTPS доступен на loopback-порту `8450`.

![Три HA-ноды банка в OrbStack и loopback HTTPS](../../screenshots/sprint10-kafka/06-orbstack-ha.png)

Для `ha` используются другой state-каталог, профиль и отдельная идентичность кластера. Смена топологии кворума не является переключателем существующего кластера. Вызовы `prepare.py` и `runtime.py create` принимают `--environment dev|test|prod`; namespace имеет вид `bank10-dev`, `bank10-test` или `bank10-prod`.

<details>
<summary>Команды отдельного HA-профиля из корня репозитория</summary>

Сначала остановить single, затем использовать отдельный state:

```bash
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" stop
export BANK_STATE="$HOME/Documents/study/java-runtime/sprint10-bank-test-ha"
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/prepare.py --output-dir "$BANK_STATE" --environment test --profile ha
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" create --environment test --profile ha --secrets-dir "$BANK_SECRETS_DIR"
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" secrets --secrets-dir "$BANK_SECRETS_DIR"
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" load-images
"$BANK_PYTHON" infra/kubernetes/deploy.py plan --project-dir "$PWD/projects/sprint10-kafka" --state-dir "$BANK_STATE" --secrets-dir "$BANK_SECRETS_DIR"
# Проверить план перед apply.
"$BANK_PYTHON" infra/kubernetes/deploy.py apply --project-dir "$PWD/projects/sprint10-kafka" --state-dir "$BANK_STATE" --secrets-dir "$BANK_SECRETS_DIR"
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" ready
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/probe.py --state-dir "$BANK_STATE" persistence
```

Проверка `persistence` создаёт собственные временные топики и пользователей Kafka, подтверждает запись, останавливает один рабочий узел с брокером/контроллером, проверяет запись при двух оставшихся брокерах/контроллерах и чтение после восстановления. В `finally` рабочий узел возвращается в исходное рабочее состояние; PVC сохраняются. Это отдельный контролируемый сценарий отказа, не шаг обычного запуска.

</details>

> При лимите OrbStack 12 ГБ полный аналитический стенд и HA-профиль запускаются последовательно. Все kind-ноды находятся в одной VM: это проверка отказа процессов и локальных рабочих узлов, без независимых дисков, датацентров или физической отказоустойчивости. Само имя namespace `prod` этих свойств не добавляет.

Kafka PVC в учебном профиле — 2 GiB на Pod. Семидневный срок хранения не гарантирует, что поток поместится на таком диске: перед увеличением нагрузки нужны расчёт ёмкости, запас свободного места и план расширения хранилища.

### Повторный запуск и остановка

```bash
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" stop
python3 projects/sprint10-kafka/deployment/scripts/init-secrets.py --lock
# Перед следующим запуском разблокировать источник секретов.
python3 projects/sprint10-kafka/deployment/scripts/init-secrets.py
"$BANK_PYTHON" projects/sprint10-kafka/deployment/kubernetes/runtime.py --state-dir "$BANK_STATE" resume
```

Остановка ограничена сохранённой идентичностью собственного кластера; приложения завершаются до хранилищ. PVC, образы, конфигурация и state сохраняются. Чужие стенды и контексты не выбираются автоматически.

## Проверки

```bash
cd projects/sprint10-kafka
./gradlew --no-daemon check packageDeployment
python3 -m unittest discover -s deployment/scripts
"$BANK_PYTHON" deployment/scripts/verify-runtime.py --state-dir "$BANK_STATE" --secrets-dir "$BANK_SECRETS_DIR" --analytics --faults
```

Java-проверки включают деньги и откат транзакций, повторные команды, JWT с настоящими подписями и HTTP JWK Set, права сервисов и владельцев, Kafka-доставку и контрактные сообщения. Общие Spring-контексты переиспользуются без `@DirtiesContext`. Инфраструктура отдельно проверяет рендеринг Helm, допустимые планы OpenTofu и реальное сохранение Kafka-данных после отказа.

Runtime-проверка проходит через HTTPS, Keycloak, Front/Gateway и настоящие хранилища: два пользователя, CSRF, баланс, перевод, история, уведомления, первое прочтение и конечная конкурентная нагрузка.

На локальном single-стенде прошли TLS/OIDC, изоляция двух владельцев, доставка и прочтение уведомлений, шесть запросов BI и независимое восстановление Cassandra/ClickHouse. Конечная нагрузка — 24 команды от четырёх клиентов и 24 повтора, наблюдаемый p95 — 0,414 с после прогрева и 4,331 с после обновления сервисов при одновременной работе BI. Это замеры двух конечных прогонов на общем стенде, без заявления SLO или предельной пропускной способности.

На HA-стенде при остановленном рабочем узле остались два брокера и два контроллера: новая запись с `acks=all` подтвердилась, после возврата ноды прежние сообщения прочитались. Все девять PVC и их тома сохранили идентичность, OOM не зафиксирован. После восстановления прошли банковские сценарии с двумя владельцами, деньгами, повторами команд и Kafka-уведомлениями.

После восстановления открыл кабинет через HTTPS/OIDC: баланс, история и Kafka-уведомления доступны. В деталях уведомления показан остаток на момент события, отдельно от текущего баланса.

![Кабинет HA-стенда: сохранённое Kafka-уведомление после восстановления](../../screenshots/sprint10-kafka/07-ha-notifications.png)

## Границы стенда

- События доставляются как минимум один раз; единая транзакция PostgreSQL и Kafka не заявляется.
- Денежные значения передаются целым количеством минимальных денежных единиц. Аналитика не проводит операции и может отставать.
- Текущий BI-дашборд ограничен месяцем UTC; личная аналитика доступна за текущий и три предыдущих месяца. Чтение одной партиции Cassandra ограничено 1000 событиями: при превышении аналитика показывает недоступность, а не неполную сумму; банковская история и проведение денег продолжают работать. Для большего объёма нужны временные партиции меньшего размера и инкрементальные агрегаты.
- Горячее и холодное хранилища ClickHouse здесь — каталоги одного сохраняемого PVC, без независимых физических носителей.
- Локальный CA, hostPath и внутренние HTTP-вызовы авторизации требуют другой настройки для внешнего промышленного окружения.
- Исполнение NetworkPolicy в стандартном kind CNI не подтверждено; изоляция сетевыми политиками здесь не заявляется. Kafka отдельно проверяет TLS, SCRAM и ACL.
- Срок хранения Kafka и локальный сохраняемый PVC не заменяют резервную копию и проверенное восстановление из него.

## Источники

- [Spring Kafka: обработка ошибок и подтверждённая публикация в DLT](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
- [Strimzi 1.3: KRaft, KafkaNodePool и постоянное хранилище](https://strimzi.io/docs/operators/1.3.0/deploying).
- [Superset: импорт активов через API](https://superset.apache.org/developer-docs/api/import-export/).
- [Caddy: выбор протоколов публичного HTTP-сервера](https://caddyserver.com/docs/caddyfile/options#protocols).
