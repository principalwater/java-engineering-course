# Собственный Spring Boot-стартер для HTTP-логирования

## Цель

Подключить логирование запросов одной зависимостью, без импорта конфигурации в приложение. Проверить изменение уровня, отключение и метаданные настроек.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16. Reactor содержит два модуля: `starter` собирает обычную библиотеку, `demo` - исполняемый JAR со встроенным Tomcat. Стартер расположен вне пакета сканирования приложения, поэтому его обнаружение зависит от `AutoConfiguration.imports`.

## Этапы выполнения

### 1. Библиотека и автоконфигурация

`HttpLoggerAutoConfiguration` регистрирует `OncePerRequestFilter`. Фильтр записывает метод и URI, затем передаёт запрос дальше по цепочке. Query, заголовки и тело не записываются; CR/LF в пути заменяются, чтобы не создавать дополнительные строки лога.

`@ConditionalOnWebApplication` ограничивает конфигурацию servlet-приложениями, `@ConditionalOnMissingBean` позволяет потребителю предоставить собственный `HttpLogger`. У библиотеки нет main-класса и Boot repackaging. Spring Web и SLF4J приходят со стартером Web; дополнительный Lombok для нескольких строк не нужен.

### 2. Настройки

`HttpLoggerProperties` связывает `application.http.logging.level` (INFO по умолчанию) и `enabled` (true). `@EnableConfigurationProperties` регистрирует binding; `@ConditionalOnProperty` отключает автоконфигурацию при false. Configuration processor создаёт `META-INF/spring-configuration-metadata.json` с типами, описаниями и defaults.

Для DEBUG нужны две настройки: уровень записи фильтра и порог самого логгера. Из каталога упражнения:

```bash
java -jar demo/target/http-logger-demo-1.0-SNAPSHOT.jar \
  --application.http.logging.level=DEBUG \
  --logging.level.dev.principalwater.study.http=DEBUG
```

Отключение: `--application.http.logging.enabled=false`. Приложение слушает `127.0.0.1:18083`; завершение - Ctrl+C.

### 3. Сборка и проверка потребителя

Из корня репозитория, с JDK 21 в PATH:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint04/http-logger/pom.xml clean install
python3 exercises/sprint04/http-logger/verify.py
```

`install` помещает библиотеку в локальный Maven-репозиторий; reactor собирает потребителя с явной версией зависимости. `DemoController` возвращает `Hello!` на GET `/demo`.

Проверка на Python 3.11+ запускает готовый JAR в трёх режимах. Во всех получен HTTP 200 и правильное тело; default записал один INFO, debug - один DEBUG, disabled не записал запрос. Маркер в query не появился в логе. В библиотечном JAR проверены обычная упаковка и сгенерированные defaults метаданных. Процессы завершаются автоматически, логи остаются в `target/server-*.log`.

Проверка прошла на JDK 21.0.12.1. JUnit здесь не добавлен: основной контракт проверяется через настоящий упакованный потребитель и HTTP. Async/error redispatch и пользовательская замена бина отдельно не проверялись; `OncePerRequestFilter` не означает одну запись на все возможные диспетчеризации запроса. Стартер рассчитан на servlet MVC, не WebFlux.

## Источники

- [Собственные автоконфигурации и стартеры](https://docs.spring.io/spring-boot/3.4/reference/features/developing-auto-configuration.html).
- [Configuration metadata](https://docs.spring.io/spring-boot/3.4/specification/configuration-metadata/annotation-processor.html).
