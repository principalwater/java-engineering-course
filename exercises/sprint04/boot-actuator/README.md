# Spring Boot Actuator и информация о сборке

## Цель

Подключить Actuator, собрать исполняемый JAR и проверить метрики приложения вместе с информацией о сборке.

## Описание

Java 21, Spring Boot 3.4.13, Maven 3.9.16 или Gradle 8.14.3, стартеры Web/Actuator. Сборщики используют общие Java-исходники и ресурсы. Для Maven используется общий wrapper первого спринта, для Gradle - второго.

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

`health` возвращает 200 и `UP`; `metrics`, `env`, `info`, `beans` и `loggers` - 404. После остановки первого процесса через Ctrl+C запускается учебный профиль:

```bash
java -jar target/boot-actuator-practice-1.0-SNAPSHOT.jar --spring.profiles.active=lab
```

### 3. Проверка метрики и сборки

```bash
python3 verify.py lab
curl --fail --silent http://127.0.0.1:18082/actuator/metrics/system.cpu.count
```

Проверка требует Python 3.11+ и обращается к настоящему HTTP-серверу. Она подтверждает доступность `env`, `beans`, `loggers`, имя и описание метрики CPU, положительное число процессоров в `measurements`, артефакт/версию/время в `info.build`. Время сравнивается с отчётом выбранного сборщика; `BuildProperties` 3.4 нормализует его до миллисекунд. Значение метрики находится внутри `measurements`, отдельного поля `value` в корне ответа нет. Содержимое окружения проверка не выводит.

Оба режима прошли проверку на JDK 21.0.12.1; JAR запущен со встроенным Tomcat 10.1.50. Сборка сама по себе не считается проверкой HTTP: JUnit-тестов в этом небольшом упражнении нет.

### 4. Альтернативная сборка Gradle

В `build.gradle` явно подключён `io.spring.dependency-management`: Boot Plugin сам по себе не заменяет политику версий. Стартеры объявлены без версий; `springBoot { buildInfo() }` создаёт метаданные, а `CommandLineRunner` выводит время в консоль.

Из корня репозитория:

```bash
./exercises/sprint02/file-transformer-gradle-plugin/gradlew --no-daemon \
  -p exercises/sprint04/boot-actuator build dependencyManagement
./exercises/sprint02/file-transformer-gradle-plugin/gradlew --no-daemon \
  -p exercises/sprint04/boot-actuator bootRun --args='--spring.profiles.active=lab'
```

В другом терминале, из каталога упражнения:

```bash
python3 verify.py lab gradle
```

`build` и `bootRun` прошли проверку. Время из консоли совпало с `build/resources/main/META-INF/build-info.properties` до миллисекунд; HTTP-проверка подтвердила именно метаданные Gradle.

В `bootJar` добавлены атрибут `Implementation-Title` и `launchScript()`. Commons Collections 4.4 объявлен как `runtimeOnly` специально для проверки упаковки, бизнес-код его не использует. В архиве подтверждены `BOOT-INF/lib/commons-collections4-4.4.jar`, атрибут манифеста и начало shell script.

После остановки `bootRun` готовый Gradle JAR запускается напрямую из каталога упражнения, с выбранным JDK 21:

```bash
./build/libs/boot-actuator-practice-1.0-SNAPSHOT.jar --spring.profiles.active=lab
```

Этот запуск также прошёл `python3 verify.py lab gradle`; прямое исполнение shell script проверено на macOS.

### 5. Liveness и собственный readiness indicator

Профиль `probes` включает две health groups. `CyclingHealthIndicator` зарегистрирован как `cycleCheck` и включён только в readiness вместе с `readinessState`. Он последовательно чередует UP/DOWN, чтобы показать агрегацию статусов; это учебная симуляция, а не оценка готовности реального сервиса.

```bash
java -jar target/boot-actuator-practice-1.0-SNAPSHOT.jar --spring.profiles.active=probes
```

В другом терминале:

```bash
python3 verify.py probes maven
```

Проверка прошла на настоящем HTTP-сервере для Maven и Gradle JAR: liveness остался UP/200, два последовательных ответа readiness дали UP/200 и DOWN/503. В подробностях readiness собственный `cycleCheck` определял общий статус при UP у `readinessState`; общий health также учитывал этот индикатор. Симуляция не включается в `default` или `lab`.

Профиль `lab` раскрывает все доступные эндпоинты только для локальной практики; стандартный запуск сохраняет раскрытие `health`. Для остановки используется Ctrl+C в терминале сервера.

### 6. События запуска и изменение доступности

`LifecycleLogger` подключён через `addListeners` до `run()`: обычный bean listener ещё недоступен для ранних событий. После обновления контекста наблюдалась последовательность `ApplicationStartedEvent` → `LivenessState.CORRECT` → runner с временем сборки → `ApplicationReadyEvent` → `ReadinessState.ACCEPTING_TRAFFIC`.

`AvailabilityDemo` запускает тот же сервер на свободном порту и после завершения `run()` публикует отказ, затем восстановление готовности. Из каталога упражнения:

```bash
../../sprint01/junit5/mvnw -B --no-transfer-progress verify dependency:build-classpath \
  -Dmdep.outputFile=target/runtime-classpath.txt
java -cp "target/classes:$(cat target/runtime-classpath.txt)" \
  dev.principalwater.study.actuator.AvailabilityDemo
```

HTTP-проверка прошла: readiness изменился UP/200 → OUT_OF_SERVICE/503 → UP/200. Демонстрация закрывает клиент и контекст сама. Изменение readiness сообщает состояние оркестратору; оно само по себе не запрещает запросы к прикладным контроллерам. Оба сборщика явно выбирают `ActuatorApplication` как основной класс исполняемого JAR.

## Источники

- [Spring Boot Maven Plugin: упаковка](https://docs.spring.io/spring-boot/3.4/maven-plugin/packaging.html).
- [Информация о сборке](https://docs.spring.io/spring-boot/3.4/maven-plugin/build-info.html).
- [Actuator: эндпоинты и раскрытие](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html).
- [Gradle: управление зависимостями](https://docs.spring.io/spring-boot/3.4/gradle-plugin/managing-dependencies.html).
- [Запуск, события и доступность приложения](https://docs.spring.io/spring-boot/3.4/reference/features/spring-application.html).
