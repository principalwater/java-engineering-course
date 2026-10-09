# Liquibase: миграции схемы магазина

## Цель

Подготовить схему для заданных JPA-сущностей средствами Liquibase и проверить её создание, повторный запуск и откат.

## Описание

В [исходном проекте](https://code.s3.yandex.net/middle-java/liquibase-homework.zip) заданы пользователь, товар и заказ. Сущности сохранены без изменения: задача касается миграций. Используются Java 21, Spring Boot 3.4.13, Gradle 8.14.3 и управляемые BOM версии Hibernate, Liquibase и H2.

Миграции находятся в `src/main/resources/db/changelog/liquibase/`. Целевая схема `yandex_practicum_homework` задана в конфигурации; файлы миграций её не переопределяют. H2 создаёт пустую схему через исходный JDBC URL. Все таблицы, sequence и ограничения создаёт Liquibase; Hibernate выполняет только `validate`.

## Выполнение

### 1. Настройка сборки

Добавлены `liquibase-core` и обработчик аннотаций Lombok: исходные сущности используют сгенерированные конструкторы и методы. Wrapper переиспользуется из спринта 2. Команда из корня репозитория:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./exercises/sprint02/file-transformer-gradle-plugin/gradlew \
  -p exercises/sprint05/liquibase test --console=plain
```

На другой машине достаточно выбрать установленный JDK 21.

### 2. Порядок миграций

Главный changelog явно включает семь файлов: три sequence с шагом 1, затем `users`, `products`, `orders` и `order_products`. Порядок обеспечивает существование объектов перед созданием внешних ключей.

Каждый changeset проверяет отсутствие создаваемого объекта и содержит rollback. При неожиданном существующем объекте `HALT` останавливает миграцию: схема с неизвестной структурой не помечается как успешно созданная. Откат идёт в обратном порядке, сначала удаляя зависимые таблицы.

### 3. Проверка на H2

Один интеграционный сценарий проверяет всю границу хранения:

- старт Boot применяет миграции, после чего Hibernate валидирует сущности;
- пользователь, товар и заказ сохраняются через JPA и читаются после `flush/clear`, включая связь товаров;
- БД отклоняет повторный username и связь с отсутствующим заказом;
- повторный запуск сохраняет данные и количество записей `DATABASECHANGELOG`;
- полный rollback удаляет таблицы, последующий update восстанавливает пустую схему.

Получено `BUILD SUCCESSFUL`: **1 тест, 0 ошибок, 0 пропусков**. Отчёт Gradle: `build/reports/tests/test/index.html` внутри упражнения.

Для rollback → update в одном JVM тест очищает `FastCheckService` Liquibase 4.29.2: его кеш хранит прежний признак актуальности. Обычные отдельные CLI-процессы этот кеш не разделяют.

## Ограничения

H2 в режиме PostgreSQL не доказывает совместимость с PostgreSQL. Здесь проверен исходный учебный стек; внешняя СУБД не запускалась. Полный rollback удаляет данные, поэтому такой план не заменяет резервную копию или миграцию восстановления.

Цена `double` и Lombok `@Data` у сущностей оставлены из исходного проекта. Для денежных расчётов понадобятся `BigDecimal` и согласованные precision/scale; при развитии модели следует отдельно пересмотреть equality и включение связей в `toString`.

## Источники

- [Spring Boot 3.4: Liquibase](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html#howto.data-initialization.migration-tool.liquibase)
- [Liquibase: changeset](https://docs.liquibase.com/concepts/changelogs/changeset.html)
