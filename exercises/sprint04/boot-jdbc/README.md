# Веб-приложение Spring Boot с JDBC и H2

## Цель

Повторить CRUD пользователей со встроенным Tomcat и автоматической настройкой DataSource/JdbcTemplate. Проверить полный путь HTTP → Controller → Service → DAO → H2.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16. Слои и JSON-модель продолжают упражнение третьего спринта; ручные Servlet initializer и WebConfiguration заменены Boot-автоконфигурацией. Используется `spring-boot-starter-jdbc`: Spring Data repositories и JPA здесь не нужны.

## Этапы выполнения

### 1. Приложение и схема

`UsersApplication` запускает встроенный сервер. В `schema.sql` находятся таблица с identity ID, ограничениями и тремя исходными пользователями; Boot загружает схему для embedded H2. DAO использует связанные SQL-параметры и явную сортировку по ID. Сервис проверяет имена, возраст и обязательный active, возвращает 404 при изменении отсутствующего пользователя.

Контракт: GET `/users` - JSON/200, POST - 201, PUT/DELETE `/users/{id}` - 204, невалидный пользователь - 400. ID новой записи выдаёт БД; переданное в JSON значение не используется для вставки.

### 2. Профили и запуск

`dev` - default profile при отсутствии активных; H2 хранится в памяти. Профиль `external` требует DB_URL/DB_USERNAME/DB_PASSWORD и отключает учебную инициализацию схемы. Он не объявлен production-конфигурацией: реальной внешней БД и миграций в упражнении нет, драйвер внешней СУБД нужно подключить отдельно.

Из корня репозитория, с JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint04/boot-jdbc/pom.xml verify
java -jar exercises/sprint04/boot-jdbc/target/boot-jdbc-practice-1.0-SNAPSHOT.jar
curl --fail --silent http://127.0.0.1:18084/users
```

Сервер привязан к loopback; завершение - Ctrl+C. In-memory данные исчезают после остановки JVM; DB_CLOSE_DELAY сохраняет их только между закрытиями соединений внутри процесса.

### 3. Интеграционная проверка

`UsersHttpTest` использует `@SpringBootTest(RANDOM_PORT)` и TestRestTemplate. Один сценарий проверяет создание и чтение UTF-8/апострофа, обновление age/active, отказ 400 без изменения БД, удаление и повторный DELETE/404. Созданная запись удаляется в finally, исходный список восстанавливается. Mockito исключён: HTTP, Jackson и H2 настоящие.

Прогон на JDK 21.0.12.1: один тест, ошибок и пропусков нет. Это проверка интеграции через случайный порт; она сама по себе не проверяет готовый JAR. Упаковка выполнена отдельно целью repackage. H2 подтверждает выбранный диалект; поведение PostgreSQL и внешнего профиля не проверялось.

## Источники

- [Spring Boot: SQL и JdbcTemplate](https://docs.spring.io/spring-boot/3.4/reference/data/sql.html).
- [Тестирование приложения](https://docs.spring.io/spring-boot/3.4/reference/testing/spring-boot-applications.html).
