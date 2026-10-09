# Практика JUnit 5

## Цель

Проверить независимость тестов и порядок lifecycle callbacks на Java 21, JUnit Jupiter 5.11.2.

## 1. Реализация

`@BeforeEach` создаёт калькулятор перед каждым тестом; сложение и вычитание проверяются отдельно. Статический `@AfterAll` выполняется после всех тестов класса. `LifecycleInheritanceTest` проверяет порядок callbacks родительского и дочернего классов, `NestedLifecycleTest` — вложенные fixtures.

## 2. Запуск

Откройте `pom.xml` как Maven-проект в IntelliJ IDEA и запустите `CalculatorTest`.

Из каталога проекта:

```sh
./mvnw -B --no-transfer-progress test
```

Для одного метода добавьте `'-Dtest=CalculatorTest#testAddition'`; для класса — `-Dtest=CalculatorTest`; для шаблона — `'-Dtest=Calculator*'`. Кавычки защищают специальные символы от оболочки. Отчёты Maven находятся в `target/surefire-reports/`.

Укажите JDK 21 в `JAVA_HOME`. Wrapper закрепляет Maven 3.9.16; его scripts сохранены с исходными уведомлениями и [Apache License 2.0](.mvn/wrapper/LICENSE).

## 3. Результат

Все четыре теста прошли на JDK 21: две операции калькулятора, lifecycle при наследовании и вложенные fixtures. Отчёт Surefire: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.
