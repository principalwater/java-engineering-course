# Справочник пользователей на Spring WebFlux

## Цель

Собрать реактивное HTML-приложение: создание, просмотр, редактирование и удаление пользователей, поиск по фамилии, загрузка и замена фотографий в БД.

## Описание

Java 21, Spring Boot 3.5.6, Gradle 8.14.3 с Kotlin DSL, WebFlux, Spring Data R2DBC, Thymeleaf и H2. Версия Boot сохранена из условия этой практики. Gradle Wrapper общий с упражнением второго спринта. Приложение использует Netty и слушает только `127.0.0.1:18087`; данные в памяти исчезают после остановки.

## Этапы выполнения

### 1. Данные и реактивная цепочка

`UserController` возвращает HTML и перенаправления, сервисы составляют Mono/Flux, репозитории выполняют R2DBC-запросы. В обработке HTTP нет block/subscribe. UserForm проверяет имена и неотрицательный возраст; идентификатор берётся из пути, а при создании назначается БД. HTML экранирует введённый текст. Поиск по фамилии выполняется без учёта регистра.

Фотография хранится в BLOB с внешним ключом и ON DELETE CASCADE. Вместо read-then-insert применяется одна H2 MERGE-команда: повторная загрузка заменяет фото без гонки между чтением и вставкой. Этот SQL зависит от H2. DataBufferUtils.join ограничивает накопление 5 МиБ, буфер освобождается в finally. Пустой файл даёт 400, превышение лимита — 413, MIME вне image/* — 415. Имя загруженного файла не используется как путь. CSP sandbox и nosniff защищают выдачу image/*, включая SVG; MIME берётся из запроса, декодирование изображения не проверяется.

| Метод и путь | Результат |
| --- | --- |
| GET /users | Список; необязательный lastName фильтрует фамилию |
| GET /users/new, /users/{id}/edit | Форма создания или редактирования |
| GET /users/{id} | Профиль; отсутствующий пользователь перенаправляется к списку |
| POST /users, /users/{id} | Создание или обновление; ошибки формы возвращают HTML с 400 |
| POST /users/{id}/delete | Удаление пользователя и его фотографии |
| POST /users/{id}/photo | Multipart с полем photo; загрузка или замена |
| GET /users/{id}/photo | Фото с сохранённым MIME либо PNG-заглушка |

### 2. Интерфейс и запуск

Thymeleaf starter добавлен для реактивных представлений; шаблоны списка и формы размещены согласно их назначению. Полупрозрачные панели, системные шрифты, видимый keyboard focus, адаптивная компоновка и fallback при reduced transparency не требуют внешних ресурсов.

Из корня репозитория с JDK 21 в PATH:

```bash
./exercises/sprint02/file-transformer-gradle-plugin/gradlew --no-daemon \
  -p exercises/sprint06/webflux-users test bootJar
java -jar exercises/sprint06/webflux-users/build/libs/webflux-users.jar
```

Открыть [список пользователей](http://127.0.0.1:18087/users). Начальные записи загружаются из data.sql. В браузере проверены переходы и создание профиля; демонстрационная запись затем удалена.

![Справочник пользователей](../../../screenshots/sprint06-webflux-users/01-user-directory.jpg)

![Заполненная форма создания](../../../screenshots/sprint06-webflux-users/02-user-form.jpg)

![Профиль после сохранения](../../../screenshots/sprint06-webflux-users/03-user-profile.jpg)

### 3. Проверка

Прошли четыре теста без ошибок и пропусков. Один интеграционный сценарий запускает настоящий HTTP-сервер, Thymeleaf и H2: проверяет список, создание с generated ID, защиту ID от подмены формой, экранирование, поиск, PNG-заглушку, загрузку и замену фото, 400/413/415, сохранность фото после ошибки, редактирование и каскадное удаление. Три WebFlux slice-теста отдельно проверяют HTML-список, заглушку и сохранённое фото с MIME.

Конкурентная нагрузка, другие СУБД и автоматический accessibility audit не проверялись. Аутентификация и долговременное хранение не входят в это упражнение.

## Источники

- [Spring Boot 3.5: Reactive Web Applications](https://docs.spring.io/spring-boot/3.5/reference/web/reactive.html).
- [Spring Framework 6.2: WebTestClient](https://docs.spring.io/spring-framework/reference/6.2/testing/webtestclient.html).
- [Spring Framework 6.2.11: DataBufferUtils](https://docs.spring.io/spring-framework/docs/6.2.11/javadoc-api/org/springframework/core/io/buffer/DataBufferUtils.html).
