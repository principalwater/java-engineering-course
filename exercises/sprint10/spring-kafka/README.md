# Spring Kafka: от UUID до постоянной DLQ

Цель — запустить producer и consumer отдельными приложениями, затем добавить типизированные заказы, повторы и сохранение необработанных сообщений.

Java 21, Spring Boot 3.5.8, Spring Kafka 3.3.11, Kafka Client 3.9.1; брокер — общий Kafka 4.0.0. Версии зависимостей согласованы Boot BOM и проверены на работающих JAR. Учебный архив содержит другой, упрощённый однопроектный пример; здесь реализована трёхмодульная структура упражнения.

## 1. Собрать три модуля

- `api` — `Order(UUID id, String username, Instant createdAt)` и имена топиков. Обязательные поля проверяются при создании, username — непустой, до 64 символов.
- `producer` — профили `uuid` и `orders`, расписание и создание топиков.
- `consumer` — listeners, обработчик ошибок, JPA-таблица и просмотр последних ошибок.

Из этого каталога:

```bash
docker compose -p java-sprint10-kafka -f ../broker/compose.yml up -d --wait --wait-timeout 120
../../sprint02/file-transformer-gradle-plugin/gradlew --no-daemon --console=plain bootJar
python3 verify.py
```

Нужны JDK 21 в `JAVA_HOME`, Docker Compose и Python 3. Проверка сама запускает и останавливает собственные JVM; другие экземпляры этих приложений должны быть завершены. Файлы H2 остаются в `target/`.

## 2. Проверить базовый обмен

По умолчанию активен `uuid`: producer отправляет UUID раз в секунду в `yandex.practicum.demo`, consumer читает строку и проверяет её формат. Топик — 3 партиции, RF=2/minISR=2. Отдельные терминалы:

```bash
java -jar producer/build/libs/producer.jar
java -jar consumer/build/libs/consumer.jar
```

Лог отправки содержит подтверждённые broker metadata; проверка сопоставляет UUID из двух процессов. Ожидание результата `send` ограничивает число незавершённых отправок одним сообщением.

## 3. Добавить заказы и повторы

```bash
java -jar producer/build/libs/producer.jar --spring.profiles.active=orders
java -jar consumer/build/libs/consumer.jar --spring.profiles.active=orders
```

Producer раз в 500 мс отправляет случайный UUID, `anonymous` и текущий Instant в `orders` (5 партиций, RF=2/minISR=2). Ключ — UUID; JSON type headers отключены, consumer использует явный тип `Order`, ограниченный trusted package и `ErrorHandlingDeserializer`.

Сценарий определяется `floorMod` младших 64 бит UUID: случайное распределение примерно 60/30/10, категория сохраняется при повторе.

| Корзина | Обработка |
| --- | --- |
| 0…5 | Успех с первой попытки |
| 6…7 | Временная ошибка; успех с третьей попытки |
| 8 | Внутренняя ошибка на всех трёх попытках; DLQ |
| 9 | `IllegalArgumentException`; DLQ без retry |

Один `DefaultErrorHandler` классифицирует исключения; `FixedBackOff(200, 2)` означает две повторные попытки после первой. Отдельные делегирующие handlers с одинаковым recoverer здесь не нужны. Номер попытки приходит из штатного delivery-attempt header; карты счётчиков в памяти нет. Ошибка десериализации и неподдерживаемый tombstone также требуют восстановления, а не молчаливого пропуска.

## 4. Сохранить DLQ перед commit offset

`dead_letter_queue` содержит topic, partition, offset, key, безопасную категорию ошибки и время. Payload и текст чужого exception не сохраняются и не выводятся. `saveAndFlush` завершает транзакцию репозитория до возврата recoverer; ошибка SQL пробрасывается, поэтому обработчик не разрешает продвинуть offset. При `AckMode.RECORD` настройка `commitRecovered` для `MANUAL_IMMEDIATE` не используется.

ID строки — `(topic, partition, offset)`: повторная доставка обновляет ту же запись. При удалении и пересоздании топика нужно включить topic ID в идентичность. Это идемпотентное SQL-восстановление, а не атомарная транзакция Kafka + БД. Каждые 5 секунд selector выводит metadata последних 20 ошибок.

После остановки consumer таблицу можно экспортировать:

```bash
../../sprint02/file-transformer-gradle-plugin/gradlew --no-daemon --console=plain :consumer:exportDlq
```

Результат — `target/dead-letter-queue.csv`. Используется H2 RunScript: SQL-ошибка завершает команду с ошибкой. Файловая H2 предназначена для одного локального экземпляра consumer; внешняя БД нужна при горизонтальном масштабировании.

## 5. Проверить отказ и replay

`verify.py` проверяет реальные JAR, broker offsets и строки SQL:

1. Сопоставляет подтверждённые UUID и заказы, проверяет попытки `1/3/3/1` для управляемых сценариев и некорректный JSON без утечки payload.
2. Перезапускает тот же group с H2 read-only. Запись в DLQ падает, committed offset не проходит проблемное сообщение, SQL-строки нет.
3. Возвращает запись в БД: сообщение обрабатывается повторно, появляется в таблице и только затем offset продвигается.
4. Сбрасывает offset только собственной неактивной группы на уже восстановленную запись. Повтор не увеличивает число строк.

Последний шаг — управляемый replay, не воспроизведение точного момента аварии между SQL commit и Kafka commit. Результаты и логи — `target/verification/<run>/`; CSV проверяется после закрытия H2. После завершения приложений общий стенд останавливается командой из [практики клиентов](../kafka-clients/README.md).

Источники: [обработка ошибок Spring Kafka 3.3](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html), [сериализация](https://docs.spring.io/spring-kafka/reference/3.3/kafka/serdes.html).
