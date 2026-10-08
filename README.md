# Java Engineering Course

[Русский](#-о-курсе) · [English](#-about-the-course)

Проектные и домашние работы по курсу [Java Middle](https://practicum.yandex.ru/middle-java/) Яндекс Практикума.

Репозиторий организован по спринтам: от приложения на Spring Framework до взаимодействия микросервисов, логирования и мониторинга. Здесь размещаются полноценные приложения с описанием решения, инструкциями по запуску и тестами.

## 📚 О курсе

Основные направления обучения:

- современная Java, многопоточность и тестирование;
- Spring Framework, Spring Boot и веб-приложения;
- работа с данными: Spring Data JPA, реактивный стек и Redis;
- REST API, OpenAPI и Spring Security;
- микросервисы, Apache Kafka, логирование и мониторинг.

## 📁 Структура репозитория

| Спринт | Проект | Планируемый каталог |
| --- | --- | --- |
| 3 | Приложение-блог на Spring Framework | `projects/sprint03-spring-blog/` |
| 4 | Перевод блога на Spring Boot | `projects/sprint04-spring-boot-blog/` |
| 5 | Витрина магазина на Spring Data JPA и Spring Web | `projects/sprint05-storefront/` |
| 6 | Витрина магазина на реактивном стеке | `projects/sprint06-reactive-storefront/` |
| 7 | RESTful-сервис, OpenAPI и Redis | `projects/sprint07-rest-service/` |
| 8 | Spring Security | `projects/sprint08-security/` |
| 9 | Микросервисное приложение банка | `projects/sprint09-bank/` |
| 10 | Взаимодействие микросервисов через Apache Kafka | `projects/sprint10-kafka/` |
| 11 | Логирование и мониторинг | `projects/sprint11-observability/` |

Каталог проекта появляется вместе с реализацией. Пока репозиторий содержит план проектных работ; готовые приложения ещё не опубликованы.

## 🎯 Цели обучения

- Разрабатывать, запускать и тестировать Java-приложения.
- Объяснять архитектурные решения и их ограничения.
- Строить веб-сервисы и работать с хранилищами данных.
- Проверять взаимодействие сервисов и сценарии отказа.
- Готовить воспроизводимое окружение и документацию проекта.

## 🔧 Технологии

Java · Spring Framework · Spring Boot · Spring Data JPA · Spring Web · Reactor · Spring Security · Redis · Apache Kafka · OpenAPI · JUnit

Базовый ориентир — Java 21. Точные версии JDK, библиотек и сборщика определяются требованиями конкретной работы и фиксируются в её README.

## 🚀 Запуск, тестирование и сдача

Каждый проект будет содержать краткое описание требований, архитектуру, конфигурацию окружения и команды сборки, запуска и тестирования. Код и тесты размещаются в стандартных каталогах выбранного сборщика. Секреты передаются через переменные окружения.

CI подключается вместе с первым приложением и выполняет его реальные проверки. Формат сдачи определяется заданием на платформе; при необходимости отдельного репозитория или ветки инструкции приводятся в README проекта.

## 📖 Материалы

- [Курс Java Middle в Яндекс Практикуме](https://practicum.yandex.ru/middle-java/)
- [Документация Java 21](https://docs.oracle.com/en/java/javase/21/)
- [Документация Spring](https://spring.io/projects)
- [Документация Apache Kafka](https://kafka.apache.org/documentation/)

Репозиторий предназначен для демонстрации проектных работ. Условия использования материалов приведены в [COPYRIGHT.md](COPYRIGHT.md).

---

# Homework Projects for the Java Engineering Course

Sprint projects and homework applications for the [Middle Java Developer course](https://practicum.yandex.ru/middle-java/) at Yandex Practicum.

The repository follows the sprint sequence, from a Spring Framework application to microservice communication, logging, and monitoring. Each application will include a solution overview, setup instructions, and tests.

## 📚 About the Course

Key learning areas:

- modern Java, concurrency, and testing;
- Spring Framework, Spring Boot, and web applications;
- data access with Spring Data JPA, the reactive stack, and Redis;
- REST APIs, OpenAPI, and Spring Security;
- microservices, Apache Kafka, logging, and monitoring.

## 📁 Repository Structure

| Sprint | Project | Planned Directory |
| --- | --- | --- |
| 3 | Blog application with Spring Framework | `projects/sprint03-spring-blog/` |
| 4 | Blog migration to Spring Boot | `projects/sprint04-spring-boot-blog/` |
| 5 | Storefront with Spring Data JPA and Spring Web | `projects/sprint05-storefront/` |
| 6 | Storefront on the reactive stack | `projects/sprint06-reactive-storefront/` |
| 7 | REST service with OpenAPI and Redis | `projects/sprint07-rest-service/` |
| 8 | Spring Security | `projects/sprint08-security/` |
| 9 | Banking microservices | `projects/sprint09-bank/` |
| 10 | Microservice communication with Apache Kafka | `projects/sprint10-kafka/` |
| 11 | Logging and monitoring | `projects/sprint11-observability/` |

Project directories are added with their implementations. The repository currently contains the project plan; completed applications have not been published yet.

## 🎯 Learning Objectives

- Develop, run, and test Java applications.
- Explain architecture decisions and their limitations.
- Build web services and work with data stores.
- Verify service interactions and failure scenarios.
- Document projects and provide reproducible environments.

## 🔧 Technologies

Java · Spring Framework · Spring Boot · Spring Data JPA · Spring Web · Reactor · Spring Security · Redis · Apache Kafka · OpenAPI · JUnit

Java 21 is the baseline. Each project's requirements determine its exact JDK, library, and build tool versions, documented in the project README.

## 🚀 Running, Testing, and Submission

Each project will document its requirements, architecture, environment configuration, and build, run, and test commands. Source code and tests follow the selected build tool's standard layout. Secrets are supplied through environment variables.

CI will be added with the first application and run its actual checks. Submission follows the platform's assignment instructions. Any requirement for a separate repository or branch will be documented in the project's README.

## 📖 Additional Resources

- [Middle Java course at Yandex Practicum](https://practicum.yandex.ru/middle-java/)
- [Java 21 Documentation](https://docs.oracle.com/en/java/javase/21/)
- [Spring Documentation](https://spring.io/projects)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)

This repository presents coursework projects. See [COPYRIGHT.md](COPYRIGHT.md) for content usage terms.
