# Redis: репозитории, кеш статей и цены

## Цель

Проверить хранение объектов с TTL, продление срока жизни статьи при чтении, декларативное кеширование цен и поиск уведомлений по получателю.

## Описание

Java 21, Spring Boot и Spring Data Redis 3.4.13, Lettuce, Redis 7.4.2-bookworm. Сборка использует общий Maven Wrapper 3.9.16. Приложение запускается как CLI: выполняет ApplicationRunner с проверками и закрывает контекст. Redis работает отдельно на `127.0.0.1:16381`.

## Этапы выполнения

### 1. Репозиторий и истечение записи

CarPrice — record с именем как ID и ценой BigDecimal; RedisHash хранит запись 10 секунд. Проверка сохраняет цену 100000.25, читает её, проверяет назначенный TTL, ждёт 12 секунд и убеждается, что запись исчезла, а индекс не содержит устаревшего ID.

Включены keyspace events на старте. На Spring Data Redis 3.4.13 двоеточие внутри имени RedisHash мешало очистке индекса: parser разделяет expiration key по первому `:`. Поэтому keyspace называется study-car-prices. Обычные ключи кеша могут содержать двоеточия. События Redis не сохраняются для отключённого приложения; эта настройка не восстанавливает пропущенные события.

### 2. Статьи и цены

PopularArticleAware сохраняет текст на сутки. getArticle читает статью и атомарно продлевает TTL через GETEX, который доступен в Redis 6.2+. Промах возвращает null. Проверены текст, суточный срок, продление после сокращения TTL, замена контента и отсутствие записи после промаха.

IPriceService реализован через Spring Cache:

| Метод | Действие |
| --- | --- |
| computePrice | Cacheable: читает prices либо вызывает Supplier и сохраняет цену |
| evictPriceFromCache | CacheEvict: удаляет один ключ prices |
| clearPricesCache | CacheEvict allEntries: очищает prices |
| upsertPriceInCache | CachePut: сохраняет возвращённую цену в отдельный car-prices |

Оба кеша имеют TTL одну минуту. Проверки вызывают внедрённый прокси из другого бина: повторное чтение не вычисляет цену, удаление разрешает новое вычисление, а операции prices не меняют car-prices. Сериализация цен оставлена стандартной JDK-сериализацией; значения BigDecimal поступают из локальной проверки.

### 3. Интеграционный тест уведомлений

MessageNotification содержит sender, receiver и content. Sender — ID, receiver индексируется, TTL — одна секунда. Повторное сохранение с тем же отправителем заменяет уведомление; это кеш последнего сообщения, а не история переписки.

Один тест запускает отдельный процесс Redis через embedded-redis 1.4.3 на свободном loopback-порту. Сохраняются два встречных сообщения: от Рагнара Лагерте «Го в Англию?» и ответ «Лол. Го.». Поиск по получателю возвращает нужное сообщение, после истечения TTL — пустой список. Ожидание ограничено пятью секундами. Сервер останавливается при закрытии Spring-контекста.

Embedded-redis использует собственный Redis binary; INFO server в тесте на этом Mac показал Redis 6.2.11. Это отдельная проверка от CLI на контейнере Redis 7.4.2, не внутрипроцессная реализация БД и не проверка всех версий Redis.

### 4. Запуск и результат

Из корня репозитория с JDK 21 в PATH:

```bash
docker run --rm -d --name java-sprint07-redis-lab \
  -p 127.0.0.1:16381:6379 redis:7.4.2-bookworm
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint07/redis-lab/pom.xml clean package
java -jar exercises/sprint07/redis-lab/target/redis-lab.jar
docker stop java-sprint07-redis-lab
```

Прошёл один интеграционный тест без ошибок и пропусков; все три группы CLI-проверок завершились успешно:

```text
Article cache: content, daily TTL, atomic refresh, update and miss passed
Price cache: proxy hit, minute TTL, separate upsert and eviction passed
Redis repository: round trip, expiration and index cleanup passed
```

CLI-проверки требуют отдельного учебного Redis: clearPricesCache очищает весь prices. Они не предназначены для запуска против общего рабочего кеша. Кластер, failover и нагрузка не проверялись. Тестовый порт выбирается перед запуском процесса; если его успеют занять, тест завершится ошибкой запуска.

## Источники

- [Spring Data Redis 3.4.13: Redis Cache](https://github.com/spring-projects/spring-data-redis/blob/3.4.13/src/main/antora/modules/ROOT/pages/redis/redis-cache.adoc).
- [Redis GETEX](https://redis.io/docs/latest/commands/getex/).
- [MappingRedisConverter 3.4.13](https://github.com/spring-projects/spring-data-redis/blob/3.4.13/src/main/java/org/springframework/data/redis/core/convert/MappingRedisConverter.java).
- [embedded-redis: запуск, binaries и зависимости](https://github.com/codemonstur/embedded-redis).
