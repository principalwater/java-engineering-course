# Проектная работа: приложение-блог на Spring Framework · Спринт 3

[REST-контракт](docs/API.md)

## Цель

Разработать REST-бэкенд блога на Java 21 и Spring Framework, подключить реляционную БД и проверить работу приложения с предоставленным фронтендом.

## Описание

Реализованы посты с Markdown и изображениями, теги, комментарии, лайки, поиск и пагинация. Для хранения данных используется PostgreSQL; отдельный контур ClickHouse демонстрирует аналитику текущего состояния блога.

## Пошаговая инструкция выполнения

### Этап 1: Проектирование приложения

Spring Boot здесь не используется. Spring-контексты и сервлет объявлены явно в `web.xml`: корневой контекст содержит БД и сервисы, дочерний — MVC. Следующий спринт переводит приложение на Boot.

```mermaid
flowchart LR
    browser[Браузер] -->|localhost:80| nginx[Nginx · React]
    browser -->|REST · localhost:8080| mvc[Tomcat · Controller]
    mvc --> service[Service · транзакции]
    service --> dao[DAO · SQL с параметрами]
    dao --> pg[(PostgreSQL)]
    ch[ClickHouse · опционально] -->|только чтение| pg
```

| Слой | Ответственность |
| --- | --- |
| Controller | HTTP, JSON, multipart, статусы ошибок |
| Service | Валидация, принадлежность комментария, транзакции |
| Model | Records, разбор поиска, превью Unicode |
| DAO | Параметризованный SQL, пакетная загрузка тегов |
| Database | Посты, упорядоченные теги, комментарии, каскадное удаление |

`schema.sql` создаёт структуру при запуске, сохраняет существующие данные. Обновление лайков выполняется атомарным SQL-инкрементом. Редактирование поста сохраняет лайки, комментарии и изображение.

#### Используемые технологии

Java 21 · Maven 3.9.16 · Spring Framework 6.2.19 · Spring Data JDBC 3.5.13 · Jackson 2.20.1 · HikariCP 6.3.3 · PostgreSQL 17 · Tomcat 10.1 · Nginx 1.29 · JUnit 5.14.4 · H2 2.4.240.

Spring Data JDBC подключён по требованиям задания; DAO использует явный Spring JDBC SQL для поиска, счётчиков и бинарных изображений. Docker-образы закреплены SHA256. Полный список зависимостей — в `pom.xml`.

![Конфигурация Maven и структура проекта в IntelliJ IDEA](../../screenshots/sprint03-spring-blog/06_idea_java21.png)

*В `pom.xml` заданы Java 21, WAR, Spring и JUnit; исходники разделены по слоям. Снимок показывает конфигурацию проекта, а не запуск тестов в IDE.*

### Этап 2: Запуск инфраструктуры

Для подготовки secrets нужен Python 3.9+. Первая сборка требует интернет для зависимостей и [предоставленного фронтенда](https://code.s3.yandex.net/middle-java/my-blog-front-app.zip). Архив проверяется SHA256, готовая сборка React не хранится в Git.

Из корня репозитория:

```sh
cd projects/sprint03-spring-blog
python3 scripts/init-secrets.py
docker compose up -d --build --wait
```

Открыть [блог](http://localhost/) и [API](http://localhost:8080/api/posts?search=&pageNumber=1&pageSize=5). Фронтенд обращается прямо к `localhost:8080`; стек предназначен для локального запуска. Сначала БД проходит healthcheck, затем бэкенд, затем фронтенд.

```sh
docker compose ps
docker compose logs backend
docker compose down
```

`down` сохраняет том БД. `POSTGRES_DB` и `POSTGRES_USER` меняют локальные имя БД и пользователя `blog`. Пароли создаются случайными файлами вне Git: по умолчанию `~/.config/java-engineering-course/sprint03/`, другой каталог задаётся `BLOG_SECRETS_DIR`. Генератор выставляет права `0700` каталогу и `0600` файлам, сохраняет существующие значения, отклоняет пустые файлы и симлинки.

Docker Compose монтирует `db_password` только в PostgreSQL и бэкенд. Пароль не передаётся через `environment`; сервисы читают `/run/secrets/db_password`. PostgreSQL применяет пароль при первичной инициализации: для существующего тома файл должен соответствовать действующему паролю роли либо пароль нужно отдельно изменить. Генератор роли и данные БД не меняет. Порты опубликованы только на `127.0.0.1`; PostgreSQL доступен внутри Docker-сети. Аутентификация пользователей блога в этом спринте не реализована.

![Контейнеры блога в OrbStack и состояние Tomcat](../../screenshots/sprint03-spring-blog/04_orbstack_backend_healthy.png)

*В OrbStack запущены бэкенд, фронтенд, PostgreSQL и опциональный ClickHouse. Tomcat прошёл проверку готовности. Одноразовый `analytics-reader` завершился успешно после настройки роли чтения.*

### Этап 3: Работа с приложением

«Тетрадь инженера»: glass-панель управления, непрозрачные карточки и текст, системные шрифты, доступные имена кнопок, видимый фокус клавиатуры, адаптивные формы. Поддержаны тёмная тема, уменьшение анимации/прозрачности и повышенный контраст. Это web-адаптация принципов материалов Apple, без зависимости от native API.

![Пост в тёмной теме](../../screenshots/sprint03-spring-blog/07_blog_dark_mode.jpg)

*Тёмная тема сохраняет контраст текста и непрозрачный фон содержимого; эффект стекла остаётся в панели управления.*

Пост создаётся карандашом; заголовок и текст обязательны, теги разделяются пробелами. Сердце добавляет лайк при каждом нажатии. Поиск совмещает фразу заголовка и все `#теги` через AND; `pageSize` задаёт размер страницы. Изображения: PNG/JPEG/GIF/BMP, до 5 МиБ и 16 мегапикселей; тип определяется по содержимому.

![Лента блога с постами, тегами и счётчиками](../../screenshots/sprint03-spring-blog/01_blog_feed.jpg)

*Лента заполнена демонстрационными постами об API, транзакциях и аналитике. Текст отображается как Markdown, превью ограничено 128 кодовыми точками Unicode.*

![Пост с кодом Java и комментариями](../../screenshots/sprint03-spring-blog/02_post_markdown_comments.jpg)

*Полный пост сохраняет текст и код. Комментарий с ID 6 успешно изменён через клиент у поста с ID 3 — исправленный маршрут не подменяет идентификатор поста.*

![Форма редактирования поста](../../screenshots/sprint03-spring-blog/03_post_edit.jpg)

*Форма редактирования содержит изображение, название, Markdown и теги.*

### Этап 4: Сборка и тестирование

```sh
java -version                  # требуется JDK 21
./mvnw -B --no-transfer-progress verify
python3 -m unittest discover -s src/test/python
python3 scripts/smoke.py        # после запуска Compose
```

Для установленной через Homebrew Java 21 на macOS перед сборкой:

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

В IntelliJ IDEA откройте `pom.xml` как Maven-проект и выберите JDK 21 в SDK проекта и настройках Maven Runner. Проверки ниже выполнены Maven на JDK 21, а не запуском JUnit из IDE.

34 сценария JUnit проверяют модель, сервис, MVC, DAO и конфигурацию. Тестовые пакеты повторяют слои приложения; общая база остаётся в `dev.principalwater.blog`. Интеграционные классы используют один кешируемый Spring TestContext и H2 в режиме совместимости с PostgreSQL. Откат сервиса проверяется настоящей ошибкой ограничения БД; конкурентные лайки — отдельными соединениями. Проверки конфигурации открывают JDBC-соединение с файловыми credentials, сохраняют значимые пробелы пароля и отклоняют конфликт источников и небезопасную CORS-политику. Две Python CLI-проверки защищают права файлов secrets, сохранение действующих паролей и отказ пустым файлам/симлинкам. `smoke.py` проверяет настоящий WAR/PostgreSQL и удаляет только созданный им пост. [GitHub Actions](../../.github/workflows/java.yml) повторяет сборку, проверки JavaScript, подготовки secrets и REST в контейнерах, сохраняет WAR и отчёты JUnit в артефакте `sprint03-java21` на 14 дней.

Артефакт: `target/blog.war`. Для собственного Tomcat 10.1 скопируйте его в `webapps/ROOT.war`, задайте параметры ниже и запустите `bin/catalina.sh run`. Локальные defaults находятся в `src/main/resources/blog.properties`; системные свойства и переменные окружения имеют приоритет.

| Параметр | Значение и назначение |
| --- | --- |
| `DB_URL` | Без переопределения — файловая H2 `./data/blog` относительно рабочего каталога. PostgreSQL: `jdbc:postgresql://localhost:5432/blog` |
| `DB_DRIVER` | H2 и PostgreSQL определяются по URL; явное значение задаёт класс установленного JDBC-драйвера |
| `DB_USER`, `DB_PASSWORD` | Credentials из окружения; для PostgreSQL обязательны и непустые. Для локальной H2 defaults — `sa` и пустой пароль |
| `DB_USER_FILE`, `DB_PASSWORD_FILE` | UTF-8-файлы вместо соответствующей переменной: нельзя задавать оба источника одновременно |
| `DB_MAX_POOL_SIZE`, `DB_POOL_NAME` | Максимум соединений и имя HikariCP-пула: `5`, `blog-database` |
| `CORS_ORIGINS` | Точные адреса через запятую: `http://localhost,http://127.0.0.1`; wildcard запрещён |
| `CORS_MAX_AGE_SECONDS` | Срок кеширования preflight: `3600` секунд; отрицательное значение запрещено |

`JdbcDataSources` создаёт пул по префиксу настроек; `DataConfig` собирает бины. `SecretValues` читает credentials отдельно: удаляет один конечный LF/CRLF файла, сохраняя пробелы. Ошибка чтения или конфликт источников останавливает запуск. Не включайте пароль в JDBC URL, Git или логи.

Compose задаёт `DB_PASSWORD_FILE=/run/secrets/db_password`; для своего Tomcat укажите доступный процессу файл и уберите `DB_PASSWORD`. [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/) разграничивают доступ к файлам, сохраняя plaintext на хосте. Это локальное решение, проверенное в OrbStack; для рабочего окружения предпочтителен внешний Vault или secret manager с ротацией. Шифрование хранения и TLS соединения настраиваются отдельно.

### Этап 5: Аналитика PostgreSQL в ClickHouse

Опциональный контур читает текущее состояние PostgreSQL через ClickHouse `postgresql()` и ранжирует посты по лайкам/комментариям. Бэкенд → PostgreSQL → аналитический запрос: нет двойной записи, CDC или отдельной истории событий. Блог работает без ClickHouse.

```sh
docker compose -f compose.yaml -f compose.analytics.yaml --profile analytics up -d --wait
docker compose -f compose.yaml -f compose.analytics.yaml exec -T clickhouse \
  sh -c 'clickhouse-client --user analyst --password "$(cat "$CLICKHOUSE_PASSWORD_FILE")" --multiquery' \
  < analytics/engagement.sql
```

Файлы `analytics_db_password` и `clickhouse_password` в том же каталоге secrets содержат отдельные пароли роли чтения и ClickHouse. `analytics-reader` получает пароль PostgreSQL и роли чтения, ClickHouse — только пароль роли чтения и свой пароль. PostgreSQL-роль `blog_reader` имеет только чтение нужных колонок; ClickHouse-профиль запрещает запись и ограничивает время, память и объём чтения. Это учебное чтение текущих данных: агрегации нагружают источник; при росте данных нужен отдельный контур загрузки/CDC. Прямое чтение источника — решение для малого локального набора, не рекомендация для производственного окружения.

![Рейтинг постов PostgreSQL в ClickHouse](../../screenshots/sprint03-spring-blog/05_clickhouse_postgres_analytics.jpg)

*ClickHouse читает три демонстрационных поста и считает комментарии. Результат отражает изменения, выполненные через REST-бэкенд.*

## Полезные источники

- [Задание спринта 3](https://docs.google.com/document/d/1ru_KPMM0M2YgsXKFF0fVDcWV_DSJ1xVyYYQUhYMbri8/edit?tab=t.0)
- [Spring DispatcherServlet](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-servlet.html), [TestContext caching](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/ctx-management/caching.html)
- [HTTP GET, RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.1)
- [ClickHouse PostgreSQL table function](https://clickhouse.com/docs/reference/functions/table-functions/postgresql)
- [Apple Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
