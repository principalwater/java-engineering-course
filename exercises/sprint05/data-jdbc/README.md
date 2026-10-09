# Spring Data JDBC и MySQL

## Цель

Проверить настройку DataSource, отображение сущности и CRUD на настоящем SQL-диалекте MySQL.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16, Testcontainers 1.20.6. Образ MySQL серии 8.4 зафиксирован digest; проверка выполнена на ARM64 через OrbStack. Spring Data JDBC не использует JPA/Hibernate: репозиторий создаёт SQL для простого aggregate `Account`.

## Этапы выполнения

### 1. Сущность и схема

`Account` - Java record с Long ID, именем и BigDecimal balance. Имена таблицы/колонок заданы явно. `ListCrudRepository` обеспечивает CRUD без ручного SQL; для вставки ID равен null, после save используется возвращённая сущность.

MySQL создаёт BIGINT AUTO_INCREMENT, VARCHAR(255) и DECIMAL(15,2). CHECK ограничивает отрицательный баланс. Схема и её автоматическая инициализация находятся только в тестовых ресурсах; обычная конфигурация не создаёт таблицы во внешней БД.

### 2. Тестовый DataSource

Nested TestConfiguration предоставляет MySQLContainer как bean с ServiceConnection. Boot получает JDBC connection details и настраивает HikariDataSource. Контейнер запускается/закрывается вместе с Spring-контекстом, поэтому не завершается раньше повторно используемого контекста. H2, Mockito и ручное назначение Docker-портов не используются.

Digest указан без tag: совмещённая запись `mysql:8.4@sha256:...` не прошла проверку совместимости в Testcontainers 1.20.6, `mysql@sha256:...` прошла.

### 3. Проверка CRUD

Из корня репозитория, с работающим Docker и JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint05/data-jdbc/pom.xml verify
```

Один интеграционный сценарий прошёл: БД выдала ID, запись с апострофом перечитана, баланс 123.45 обновлён и перечитан без потери точности, удаление подтверждено. SQL виден в DEBUG-логе JdbcTemplate. Созданная запись удаляется в finally; Testcontainers убирает собственную БД после завершения JVM.

Проверка выполнена на JDK 21.0.12.1. Отрицательный баланс, параллельные операции и транзакционный перевод на этом этапе не проверялись. Упражнение работает через репозиторий; HTTP-сервера и исполнения готового JAR здесь нет.

## Источники

- [Spring Boot: Testcontainers и ServiceConnection](https://docs.spring.io/spring-boot/3.4/reference/testing/testcontainers.html).
- [Spring Data JDBC: сохранение сущностей](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html).
