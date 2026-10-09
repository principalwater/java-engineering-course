# Реактивная выборка счетов на MySQL

## Цель

Проверить Query Method Spring Data R2DBC на настоящем MySQL: получить счета с балансом строго выше заданного порога.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16. Версии из Boot dependency management: io.asyncer:r2dbc-mysql 1.3.2, Testcontainers 1.20.6. MySQL 8.4 зафиксирован digest; публичное зеркало Docker Official Images в Amazon ECR сохраняет тот же образ. Локальный прогон выполнен на ARM64 через OrbStack.

## Этапы выполнения

### 1. Сущность и репозиторий

`Account` содержит Spring Data Id, имя и BigDecimal balance; конструктор без явного баланса задаёт 10000.00. `AccountRepository.findAccountsByBalanceGreaterThan` формирует SQL с условием `>` и связанным параметром. `AccountService` возвращает Flux для сохранения и выборки; subscribe/block в сервисе отсутствуют.

### 2. Настоящая БД

Nested TestConfiguration предоставляет MySQLContainer как bean с ServiceConnection для R2dbcConnectionDetails. Spring управляет жизненным циклом контейнера вместе с тестовым контекстом. Хост, случайный порт и учётные данные передаются автоматически.

Схема находится только в test resources и загружается с sql.init.mode=always. В ней AUTO_INCREMENT, DECIMAL(15,2), DEFAULT 10000.00 и CHECK неотрицательного баланса. JDBC Connector нужен Testcontainers для проверки готовности MySQL; операции приложения выполняет R2DBC-драйвер. Контейнер не подменяется H2 compatibility mode.

### 3. Интеграционная проверка

Из корня репозитория, с работающим Docker и JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint06/r2dbc-mysql/pom.xml verify
```

Один сценарий прошёл без ошибок и пропусков. Сохранены три счета с уникальными положительными ID. При пороге 10000.00 найден только счёт с 25000.50; счёт ровно на границе исключён, UTF-8 и апостроф сохранены. Отрицательный баланс отклонён CHECK, число строк осталось равным трём. Созданные записи удаляются в finally, ожидание каждого reactive результата ограничено 15 секундами.

Проверяются только выбранный MySQL-диалект и драйвер. Перевод денег, конкурентные обновления, retry, производительность saveAll и другие СУБД здесь не проверялись. SaveAll не объявлен одной batch-командой или атомарной транзакцией. Это тестовое упражнение без HTTP-интерфейса.

## Источники

- [Spring Data R2DBC 3.4: Query Methods](https://github.com/spring-projects/spring-data-relational/blob/3.4.x/src/main/antora/modules/ROOT/pages/r2dbc/query-methods.adoc).
- [Spring Boot 3.4: Testcontainers и service connections](https://docs.spring.io/spring-boot/3.4/reference/testing/testcontainers.html).
- [R2DBC MySQL](https://github.com/asyncer-io/r2dbc-mysql).
