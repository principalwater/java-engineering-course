# Spring Boot Actuator и информация о сборке

## Цель

Подключить Actuator, собрать исполняемый JAR и проверить метрики приложения вместе с информацией о сборке.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16, стартеры Web/Actuator. Стандартная точка входа запускает приложение; профили определяют раскрытие эндпоинтов. Для сборки используется общий Maven Wrapper первого спринта.

Spring Initializr при выполнении был недоступен из рабочего окружения. Базовый каркас создан вручную и открыт как Maven-проект в отдельном окне IntelliJ IDEA.

## Этапы выполнения

### 1. Настройка и сборка

В `pom.xml` указан Boot parent, Java 21 и execution цели `build-info`. Parent также задаёт execution `repackage`; результат содержит загрузчик Boot и вложенные зависимости.

Из корня репозитория, с выбранным JDK 21:

```bash
./exercises/sprint01/junit5/mvnw -B --no-transfer-progress \
  -f exercises/sprint04/boot-actuator/pom.xml clean verify
cd exercises/sprint04/boot-actuator
```

`verify` тоже создаёт информацию о сборке: эта фаза проходит предыдущие этапы Maven. Собран файл `target/boot-actuator-practice-1.0-SNAPSHOT.jar`; в его манифесте `Main-Class` - `org.springframework.boot.loader.launch.JarLauncher`, `Start-Class` - `dev.principalwater.study.actuator.ActuatorApplication`.

### 2. Раскрытие эндпоинтов

Приложение слушает только `127.0.0.1:18082`, чтобы не занимать порт проектного блога. Первый запуск использует стандартное раскрытие Actuator:

```bash
java -jar target/boot-actuator-practice-1.0-SNAPSHOT.jar
```

В другом терминале, из каталога упражнения:

```bash
python3 verify.py default
```

`health` возвращает 200 и `UP`; `metrics`, `env` и `info` - 404. После остановки первого процесса через Ctrl+C запускается учебный профиль:

```bash
java -jar target/boot-actuator-practice-1.0-SNAPSHOT.jar --spring.profiles.active=lab
```

### 3. Проверка метрики и сборки

```bash
python3 verify.py lab
curl --fail --silent http://127.0.0.1:18082/actuator/metrics/system.cpu.count
```

Проверка требует Python 3.11+ и обращается к настоящему HTTP-серверу. Она подтверждает доступность `env`, имя и описание метрики CPU, положительное число процессоров в `measurements`, артефакт/версию/время в `info.build`. Значение метрики находится внутри `measurements`, отдельного поля `value` в корне ответа нет. Содержимое окружения проверка не выводит.

Оба режима прошли проверку на JDK 21.0.12.1; JAR запущен со встроенным Tomcat 10.1.50. Maven-сборка сама по себе не считается проверкой HTTP: JUnit-тестов в этом небольшом упражнении нет.

Профиль `lab` раскрывает все доступные эндпоинты только для локальной практики; стандартный запуск сохраняет раскрытие `health`. Для остановки используется Ctrl+C в терминале сервера.

## Источники

- [Spring Boot Maven Plugin: упаковка](https://docs.spring.io/spring-boot/3.4/maven-plugin/packaging.html).
- [Информация о сборке](https://docs.spring.io/spring-boot/3.4/maven-plugin/build-info.html).
- [Actuator: эндпоинты и раскрытие](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html).
