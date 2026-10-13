# Модульный деплой Java-приложений

[Репозиторий курса](../README.md) · [Модули](#модули-и-точки-входа) · [Деплой](#план-и-деплой) · [Проверка](#проверка) · [Восстановление](#данные-и-восстановление)

Общий корневой модуль разворачивает Java-приложения отдельными Docker-ресурсами. У каждого стенда свои сеть, контейнеры и постоянные тома. Каталоги принятых проектов, сборочные wrappers, Dockerfile и команды Compose сохраняются.

## Версии и область применения

Проверено с **OpenTofu 1.11.6**, **Docker provider 4.6.0**, Java 21 и OrbStack на macOS. В CI используется та же версия OpenTofu на Linux. HCL рассчитан на ветку 1.11; совместимость с Terraform CLI отдельно не проверялась. Источник провайдера указан явно: `registry.terraform.io/kreuzwerker/docker`. Контрольные суммы для macOS ARM64 и Linux AMD64/ARM64 зафиксированы в lock-файле.

Это локальная учебная инфраструктура. Local backend блокирует state каждого стенда; для совместного рабочего окружения нужен отдельный зашифрованный remote backend с блокировками и контролем доступа. Выберите его до совместного использования state.

## Модули и точки входа

| Селектор | Корневой модуль | Приложение | Фронтенд | Бэкенд | Опциональный ClickHouse |
| --- | --- | --- | --- | --- | --- |
| `sprint03` | `module.sprint03[0]` | [Spring Framework WAR](../projects/sprint03-spring-blog/) | `28083` | `29083` | `28123` |
| `sprint04` | `module.sprint04[0]` | [Spring Boot JAR](../projects/sprint04-spring-boot-blog/) | `28084` | `29084` | `28124` |
| `sprint05-postgresql` | `module.sprint05["sprint05-postgresql"]` | [Витрина с PostgreSQL](../projects/sprint05-storefront/) | `28085` | Та же точка входа | `28125` |
| `sprint05-h2` | `module.sprint05["sprint05-h2"]` | [Витрина с H2](../projects/sprint05-storefront/) | `28086` | Та же точка входа | `28126` |
| `sprint06-postgresql` | `module.sprint06["sprint06-postgresql"]` | [Реактивная витрина с PostgreSQL](../projects/sprint06-reactive-storefront/) | `28087` | Та же точка входа | `28127` |
| `sprint06-h2` | `module.sprint06["sprint06-h2"]` | [Реактивная витрина с H2](../projects/sprint06-reactive-storefront/) | `28088` | Та же точка входа | `28128` |
| `docker-exercise` | `module.docker_exercise[0]` | [Docker: маршрутизация и JDBC](../exercises/supplements/docker/) | `28380` | Внутри сети | - |
| `mvc-exercise` | `module.mvc_exercise[0]` | [MVC/JDBC WAR с H2](../exercises/sprint03/spring-mvc-jdbc/) | `28081/spring-web-demo` | Та же точка входа | - |

Все опубликованные порты привязаны к `127.0.0.1`; порты БД доступны внутри сети своего стенда. Имена начинаются с `java-tofu-<selector>`. Существующие ресурсы Compose и их тома БД не подключаются к управлению OpenTofu.

Два экземпляра модуля [blog](modules/blog/) переиспользуют одинаковую конфигурацию БД, фронтенда и аналитики. Параметр `spring_boot` сохраняет различия проектов: WAR/Tomcat или Executable JAR, имя файла пароля, настройку CORS и endpoint готовности. Модуль [postgres](modules/postgres/) используется блогами, витриной с PostgreSQL и [Docker-упражнением](modules/docker-exercise/); [MVC-упражнение](modules/mvc-exercise/) сохраняет встроенную H2. У хранилища блога отдельный жизненный цикл: отключение аналитики удаляет её контейнеры, но оставляет защищённый том. Пустой том создаётся и в случае, когда аналитика ещё не включалась. [Витрина](modules/storefront/) выбирает профиль PostgreSQL/H2; её ClickHouse получает факты через outbox. Спринт 5 использует JDBC и Tomcat, спринт 6 — native R2DBC и Netty. Параметры `database_protocol`, `database_password_property` и `session_cookie_property` сохраняют различия URL, configtree и сессионных cookies. Defaults совместимы со спринтом 5; оба спринта и каждый вариант БД имеют отдельные namespaces cookies. При H2 отдельный контейнер БД не создаётся, новый процесс получает новый UUID набора данных.

```mermaid
flowchart LR
    root[main.tf] --> s3[Блог на Spring Framework]
    root --> s4[Блог на Spring Boot]
    root --> store[Витрина PostgreSQL или H2]
    root --> docker[Docker-упражнение]
    root --> mvc[MVC-упражнение]
    s3 --> pg[Модуль PostgreSQL]
    s4 --> pg
    docker --> pg
    store -. профиль PostgreSQL .-> pg
    store -. outbox .-> facts[Факты заказов в ClickHouse]
    s3 -. опционально .-> ch[Существующая интеграция ClickHouse для чтения]
    s4 -. опционально .-> ch
```

Остальные упражнения сохраняют описанный в их README запуск через JAR/Maven/Gradle. Для нового проекта с Docker-сервисами добавляются вызов модуля, селектор и проверка настоящего окружения. Переносить исходники или создавать пустые модули под будущие спринты не требуется.

## План и деплой

Нужны OpenTofu и работающий локальный Docker Engine. Для упаковки двух упражнений требуется Java 21; образы блогов собираются их wrappers внутри Docker. На macOS с Java из Homebrew:

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
tofu version
docker context show

python3 infra/deploy.py sprint04 plan --analytics
python3 infra/deploy.py sprint04 apply --analytics
python3 infra/deploy.py sprint04 status
```

Откройте [блог на Spring Boot](http://127.0.0.1:28084). Для другого стенда выберите его селектор; для базового блога уберите `--analytics`. Реактивная витрина запускается селекторами `sprint06-postgresql` и `sprint06-h2`, когда её исходники доступны в checkout. В PostgreSQL-варианте используется `r2dbc:postgresql://db:5432/storefront` и файл `/run/secrets/spring.r2dbc.password`; H2 создаётся в самом приложении. Значение этой опции должно совпадать при подготовке плана и его применении.

Скрипт определяет локальный Docker socket из активного контекста, инициализирует выбранный backend, выполняет `validate`, сохраняет план и проверяет его безопасность. `apply` применяет именно сохранённый план. Изменение исходников, конфигурации, checkout или параметров деплоя требует нового плана. После успешного применения файл плана удаляется, чтобы исключить случайный повтор.

State и планы по умолчанию находятся в `~/.local/state/java-engineering-course/<selector>/`, секреты - в `~/.config/java-engineering-course/tofu/<selector>/`. Другие внешние каталоги задаются через `COURSE_DEPLOY_STATE` и `COURSE_DEPLOY_SECRETS`. Права каталогов - `0700`, файлов паролей и планов - `0600`. Существующие пароли сохраняются. Не используйте один каталог state для разных Docker Engines и не управляйте одним стендом одновременно из разных worktrees.

Путь к CLI задаётся через `TF_CLI`; проверенный runtime - `tofu`. Для штатных команд в уже инициализированном стенде:

```sh
export TF_DATA_DIR="$HOME/.local/state/java-engineering-course/sprint04/providers"
tofu -chdir=infra output -json deployment
```

## Упаковка Java и готовность сервисов

- `docker_image` собирает существующие Dockerfile через Buildx. Хеши исходников вызывают пересборку затронутого образа; сохраняются преимущества кешей Maven/Gradle и послойного Boot JAR. Изменение Dockerfile не вводит другой формат упаковки Java-приложения.
- WAR/JAR упражнений собираются существующим Maven wrapper перед подготовкой плана. Временные метки архивов фиксированы для воспроизводимости: повторная упаковка неизменённых исходников не вызывает новый деплой.
- Готовность PostgreSQL проверяет `pg_isready`. Блог запускается после готовности БД; Boot readiness учитывает доступность БД. Docker-упражнение проверяет `/movies` через Nginx и Java. Создание контейнера само по себе не подтверждает готовность приложения.
- Java-контейнеры ограничены 512 МиБ RAM, swap отключён; ClickHouse получает 768 МиБ. Это ограничения локального стенда, а не расчёт ресурсов рабочего окружения. Healthcheck сам по себе не перезапускает нездоровый процесс.
- Boot/Java-процессы без root используют числовые UID:GID владельца на хосте, чтобы читать смонтированные секреты с правами `0600` на Linux. Пароли монтируются только для чтения; Java/entrypoint получают их при запуске. Содержимое секретов не попадает в переменные Terraform, аргументы сборки образа, конфигурацию контейнера и state.
- Блоги переиспользуют существующую интеграцию PostgreSQL/ClickHouse с ролью чтения и ограниченными привилегиями. Аналитика опциональна. File secrets - локальный способ передачи пароля, а не шифрование или замена Vault; рабочему окружению нужны внешний secret manager и отдельные настройки TLS/аутентификации.

## Проверка

```sh
tofu fmt -check -recursive infra
python3 -m unittest discover -s infra/tests -v
TF_DATA_DIR="$HOME/.local/state/java-engineering-course/sprint04/providers" tofu -chdir=infra test
python3 infra/verify.py sprint04 --analytics

python3 infra/deploy.py sprint06-postgresql plan --analytics
python3 infra/deploy.py sprint06-postgresql apply --analytics
python3 infra/verify.py sprint06-postgresql --analytics
```

Проверка настоящего окружения проходит путь HTTP и native JDBC/R2DBC, создаёт собственную контрольную запись, готовит замену только контейнера БД, применяет сохранённый план и повторно читает эту запись. Затем удаляет свою запись и требует пустого итогового плана. Вариант MVC проверяет настоящий servlet/H2 endpoint и повторный план; сохранность данных H2 после пересоздания процесса не гарантируется.

Локально проверены шесть селекторов спринтов 3–5 и двух упражнений. Для спринта 6 выполнены mock-проверки native R2DBC/H2, password mount и изоляции cookies; они не подтверждают реальную связность Netty/PostgreSQL/ClickHouse. Вариант Boot также переиспользует полный REST smoke-сценарий проекта. CI проверяет форматирование, штатные тесты конфигурации, правила безопасности плана и настоящий деплой Docker-упражнения с пересозданием БД. Проверка `infrastructure` входит в условия `/approve`. У проекта спринта 6 отдельная проверка `sprint06`: Gradle tests/bootJar, готовый JAR с H2, PostgreSQL, доставка и восстановление ClickHouse, затем HTTP-формы в Compose. При отсутствии исходников job пропускается; согласование проверяет marker на точном head PR.

## Данные и восстановление

Постоянные тома БД и аналитики защищены через `prevent_destroy`. Полный destroy или смена селектора внутри того же state завершаются отказом вместо удаления данных. Проверка сохранённого плана также отклоняет удаление томов, чужие имена ресурсов, привилегированные контейнеры, публичные привязки портов, пароли в параметрах и writable/отслеживаемые Git mounts секретов.

Для намеренного редеплоя сервиса укажите его точный адрес и проверьте план:

```sh
python3 infra/deploy.py sprint04 plan --analytics \
  --replace 'module.sprint04[0].docker_container.backend'
python3 infra/deploy.py sprint04 apply --analytics
```

Для остановки локального стенда остановите только его явно перечисленные работающие контейнеры. Следующий план покажет остановленное состояние; применение проверенного плана запускает их снова. Данные PostgreSQL и ClickHouse сохраняются в именованных томах. Не используйте общие команды prune.

Перед изменением версии образа БД выгрузите данные и сохраните резервную копию state. Откат приложения выполняется через предыдущую версию исходников и новый проверенный план; сохранённые данные при этом не откатываются. Изменение файла начального пароля не меняет пароль уже созданной роли PostgreSQL.

Окончательное удаление - отдельная операция: сделайте резервную копию данных, проверьте `tofu state list`, отсоедините только точные адреса защищённых томов через `tofu state rm`, затем проверьте штатный `tofu plan -destroy` с тем же backend и параметрами деплоя. Отсоединённые Docker-тома остаются до их явного удаления. Не отсоединяйте тома при обычном редеплое.

## Источники

- [Local backend OpenTofu](https://opentofu.org/docs/language/settings/backends/local/)
- [Сборка образов и Buildx в Docker provider](https://github.com/kreuzwerker/terraform-provider-docker/blob/v4.6.0/docs/resources/image.md)
- [Готовность контейнеров и ожидание healthcheck](https://github.com/kreuzwerker/terraform-provider-docker/blob/v4.6.0/docs/resources/container.md)
- [Контейнерные образы Spring Boot](https://docs.spring.io/spring-boot/3.5/reference/packaging/container-images/index.html)
- [Инициализация PostgreSQL Official Image](https://github.com/docker-library/docs/blob/master/postgres/README.md)
