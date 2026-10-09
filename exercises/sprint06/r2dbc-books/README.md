# Книги: реактивный репозиторий и сервис

## Цель

Выполнить сохранение, удаление и проверку отсутствия книги одной реактивной цепочкой через Spring Data R2DBC.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16. `BookRepository` расширяет ReactiveCrudRepository; `BookService.saveAndDeleteBook(name)` возвращает Mono<Void>. Сервис использует связанные операции без внутренних subscribe/block. В упражнении применяется настоящий R2DBC-драйвер H2, без JDBC и Mockito.

## Этапы выполнения

### 1. Схема и сущность

Boot инициализирует in-memory H2 из `schema.sql`. `Book` — record с Spring Data Id; identity в БД выдаёт Integer ID. Название обязательно, размер VARCHAR — 255. Сервис отклоняет null и пустое название до записи.

### 2. Последовательность операций

Сохранённая сущность передаёт ID в лог и следующую операцию. После deleteById оператор then подписывается на existsById. Если запись осталась, цепочка завершается ошибкой; иначе сообщает false и завершается без значения. Вызывающий код подписывается на возвращённый Mono.

Операции выполняются по порядку, без общей транзакции. Ошибка после успешной вставки может оставить книгу в БД; упражнение не обещает атомарности. Независимые подписки и автоматический retry здесь не используются.

### 3. Проверка на БД

Из корня репозитория, с JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint06/r2dbc-books/pom.xml verify
```

Один интеграционный сценарий SpringBootTest/StepVerifier прошёл без ошибок и пропусков: получен положительный ID, результат exists — false, соседняя книга сохранилась, некорректные названия не добавили строк. Ожидание ограничено пятью секундами; sleeps и mocks не используются.

H2 проверяет выбранный драйвер и схему. Синтаксис MySQL AUTO_INCREMENT заменён H2 identity; MySQL/PostgreSQL, нагрузка и сетевые отказы не проверялись. Приложение не содержит HTTP-интерфейса; сценарий запускается интеграционным тестом.

## Источники

- [ReactiveCrudRepository 3.4](https://github.com/spring-projects/spring-data-commons/blob/3.4.x/src/main/java/org/springframework/data/repository/reactive/ReactiveCrudRepository.java).
- [Reactor: StepVerifier](https://projectreactor.io/docs/core/release/reference/testing.html).
- [Spring Boot 3.4: SQL](https://docs.spring.io/spring-boot/3.4/reference/data/sql.html).
