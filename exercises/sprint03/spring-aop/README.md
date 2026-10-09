# Спринт 3 — логирование методов через Spring AOP

## Цель

Добавить замер времени и логирование входных параметров строковых методов `UserController`, сохранив их результат и исключения.

## Описание

В контроллере три операции: получение пользователя по имени, создание из `User` и удаление по имени. Аспект перехватывает методы с одним параметром `String`: `getUser` и `deleteUser`. `createUser(User)` выполняется без замера. Данные хранятся в памяти; пример проверяет Spring-прокси, HTTP-сервер здесь не запускается.

## Выполнение

1. Зависимости закреплены: JDK 21, Spring Framework 6.2.19, AspectJ 1.9.22.1, JUnit 5.11.2. Конфигурация регистрирует сервис, контроллер и аспект через `@Bean`; `@EnableAspectJAutoProxy` включает Spring AOP.
2. `UserTimingAspect` использует `@Around` и `ProceedingJoinPoint`. Возвращается результат `proceed()`, исключение проходит вызывающему коду. `System.nanoTime()` измеряет длительность, а `finally` сохраняет замер и при ошибке. Журнал содержит имя метода, параметры и `elapsedNanos`.
3. Из корня репозитория запускается проверка; используется уже закреплённый Maven wrapper:

```bash
exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint03/spring-aop/pom.xml clean test
```

Один интеграционный сценарий вызывает настоящий Spring-прокси: создание не попадает в журнал, чтение возвращает исходного пользователя, удаление меняет состояние, повторное чтение возвращает исходное исключение. Журнал проверяется для успешных и ошибочного вызовов.

Результат на JDK 21.0.12.1: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

## Уточнения реализации

В примере для `@Around` нужен `ProceedingJoinPoint`, а возвращаемый тип — `Object`: обычный `JoinPoint` не имеет `proceed()`, и `void` не сохраняет результат чтения. Использован `proxyBeanMethods`, корректное имя атрибута `@Configuration`; DELETE получил путь `/{name}` для соответствующего `@PathVariable`. Поддержка аннотаций AspectJ здесь включает Spring-прокси, а не weaving байт-кода.

## Источники

- [Советы и возврат результата](https://docs.spring.io/spring-framework/reference/6.2/core/aop/ataspectj/advice.html).
- [Точки среза](https://docs.spring.io/spring-framework/reference/6.2/core/aop/ataspectj/pointcuts.html).
- [Границы Spring-прокси](https://docs.spring.io/spring-framework/reference/6.2/core/aop/proxying.html).
