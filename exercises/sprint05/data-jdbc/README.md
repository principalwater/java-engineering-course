# JDBC и MySQL: от репозитория к собственному SQL

## Цель

Проверить настройку DataSource, отображение сущности и CRUD на настоящем SQL-диалекте MySQL.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16, Testcontainers 1.20.6. Образ MySQL серии 8.4 зафиксирован digest; проверка выполнена на ARM64 через OrbStack. Финальный вариант использует JdbcTemplate для счетов и Spring Data JDBC для уведомлений, без JPA/Hibernate.

## Этапы выполнения

### 1. Сущность и схема

В первом варианте `Account` - Java record с Long ID, именем и BigDecimal balance; таблица/колонки заданы явно в Spring Data annotations. `ListCrudRepository` обеспечивал CRUD без ручного SQL; для вставки ID равен null, после save использовалась возвращённая сущность.

MySQL создаёт BIGINT AUTO_INCREMENT, VARCHAR(255) и DECIMAL(15,2). CHECK ограничивает отрицательный баланс. Схема и её автоматическая инициализация находятся только в тестовых ресурсах; обычная конфигурация не создаёт таблицы во внешней БД.

### 2. Тестовый DataSource

Nested TestConfiguration предоставляет MySQLContainer как bean с ServiceConnection. Boot получает JDBC connection details и настраивает HikariDataSource. Контейнер запускается/закрывается вместе с Spring-контекстом, поэтому не завершается раньше повторно используемого контекста. H2, Mockito и ручное назначение Docker-портов не используются.

Образ загружается из публичного зеркала Docker Official Images в Amazon ECR с тем же digest, чтобы CI не зависел от лимита анонимных загрузок Docker Hub. Совместимость с MySQL явно объявлена через `asCompatibleSubstituteFor("mysql")`; digest указан без tag.

### 3. Проверка CRUD

Из корня репозитория, с работающим Docker и JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint05/data-jdbc/pom.xml verify
```

Один интеграционный сценарий прошёл: БД выдала ID, запись с апострофом перечитана, баланс 123.45 обновлён и перечитан без потери точности, удаление подтверждено. SQL виден в DEBUG-логе JdbcTemplate. Созданная запись удаляется в finally; Testcontainers убирает собственную БД после завершения JVM.

Проверка выполнена на JDK 21.0.12.1. Первый вариант работал через репозиторий; HTTP-сервера и исполнения готового JAR здесь нет.

### 4. Транзакционный перевод

Первый вариант `TransferService` использовал PlatformTransactionManager: открывал scope, сначала начислял получателю, затем списывал отправителю. При ошибке SQL выполнял rollback и возвращал исключение вызывающему коду; commit находился за пределами catch. DAO меняет баланс арифметикой в SQL с именованными параметрами, без чтения и перезаписи устаревшей суммы. Сервис отклоняет некорректные ID, неположительную сумму и дробную часть точнее двух знаков.

Второй сценарий на MySQL подтвердил commit: балансы 10000/10000 превратились в 9500/10500. Перевод 100000 нарушил CHECK у отправителя, и оба баланса сохранили предыдущие значения, включая отмену уже выполненного начисления. Сумма 0.001 отклонена до изменения записей. Оба теста используют один контекст и собственный контейнер; Mockito не используется.

Прогон: два теста, ошибок/пропусков нет. Конкурентные переводы, deadlock/retry и crash recovery не проверялись; credit-first порядок служит демонстрации rollback, перед конкурентным использованием нужен согласованный порядок блокировок.

### 5. Переход на TransactionTemplate

Ручные getTransaction/commit/rollback заменены одним `executeWithoutResult`. Используется bean, который Boot 3.4.13 создаёт при единственном PlatformTransactionManager и отсутствии своего TransactionOperations. Отдельная конфигурация дефолтов не нужна; настройки общего template не изменяются в момент вызова.

Те же два интеграционных теста прошли на MySQL: commit, полный rollback после ошибки списания и валидация сохранились. Дополнительные тесты, повторяющие прежние проверки, не добавлены.

### 6. Декларативная транзакция

Следующий вариант заменил callback на `@Transactional` у публичного `transfer`. Сервис получает только репозиторий; тест вызывает сервис через внедрённый Spring bean и тем самым проходит через proxy. Обработка исключений не скрывает ошибку от transaction interceptor.

Оба прежних теста вновь прошли на настоящем MySQL. История коммитов сохраняет варианты PlatformTransactionManager и TransactionTemplate для сравнения; окончательный код использует аннотацию. Метод не отправляет JDBC-операции в другой поток.

### 7. Собственный SQL через JdbcTemplate

Репозиторий заменён на `AccountDao`: findAll, findFirstByName, create и update используют явный SQL и общий RowMapper. FindAll упорядочен по ID; поиск имени передаёт значение отдельным аргументом и выбирает первую запись по ID. Отсутствие записи даёт EmptyResultDataAccessException. Дополнительный adjustBalance нужен реальному AccountService для атомарной арифметики в БД.

`AccountService` сохраняет транзакционную границу; Account стал обычным record без persistence annotations. Стартер Data JDBC заменён на JDBC, так как автоматические Spring Data repositories больше не используются. DEFAULT начального баланса теперь принадлежит схеме MySQL.

Два интеграционных теста адаптированы к DAO и прошли. Они по-прежнему проверяют ID, DECIMAL, commit/rollback, а также подтверждают, что `x' OR 1=1 -- ` воспринимается как буквальное имя, не возвращает чужую запись и не меняет SQL. Тестовые записи удаляются напрямую через JdbcTemplate по собственным ID: дополнительный production API для cleanup не создавался.

### 8. Три API вставки уведомлений

Добавлена таблица notification с BIGINT AUTO_INCREMENT и VARCHAR(255) NOT NULL. Стартер Data JDBC снова подключён для NotificationRepository; доступ к account остаётся через собственный DAO. NotificationDao предлагает два варианта вставки: JdbcTemplate.batchUpdate и заранее настроенный SimpleJdbcInsert. В обоих идентификаторы должны отсутствовать, их выдаёт БД; пустой список не отправляется драйверу. SimpleJdbcInsert использует только message, id объявлен generated-key column.

Один параметризованный тест сравнивает saveAll, batchUpdate и executeBatch: каждый сохраняет 100 сообщений с проверкой всего содержимого и уникальных ID. Отдельный сценарий подтверждает rollback batchUpdate после NOT NULL ошибки во второй записи: первая запись также не остаётся в БД. DAO-методы помечены Transactional - batch сам по себе не обеспечивает атомарность.

Итоговый прогон: шесть тестовых случаев, ошибок и пропусков нет, один Spring-контекст и один MySQL-контейнер. Сетевые round-trip и ускорение не измерялись; форма batching зависит от JDBC-драйвера.

## Источники

- [Spring Boot: Testcontainers и ServiceConnection](https://docs.spring.io/spring-boot/3.4/reference/testing/testcontainers.html).
- [Spring Data JDBC: сохранение сущностей](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html).
- [Программные транзакции](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html).
- [Декларативные транзакции](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html).
- [JDBC batch operations](https://docs.spring.io/spring-framework/reference/6.2/data-access/jdbc/advanced.html).
