# Java Engineering Course

[![CI](https://github.com/principalwater/java-engineering-course/actions/workflows/java.yml/badge.svg?branch=main)](https://github.com/principalwater/java-engineering-course/actions/workflows/java.yml)

[Русский](#java-engineering-course) · [English](#homework-projects-for-the-java-engineering-course)

**[Проекты](projects/) · [Упражнения в IDE](exercises/) · [Сборка и тесты](#-запуск-тестирование-и-сдача)**

Проектные и домашние работы по курсу [Java Middle](https://practicum.yandex.ru/middle-java/) Яндекс Практикума.

Репозиторий организован по спринтам: от приложения на Spring Framework до взаимодействия микросервисов, логирования и мониторинга.

## 📚 О курсе

Основные направления обучения:

- современная Java, многопоточность и тестирование;
- Spring Framework, Spring Boot и веб-приложения;
- работа с данными: Spring Data JPA, реактивный стек и Redis;
- REST API, OpenAPI и Spring Security;
- микросервисы, Apache Kafka, логирование и мониторинг.

## 📁 Структура репозитория

Полноценные приложения размещаются в [projects/](projects/), внешние упражнения для IDE - в [exercises/](exercises/). Упражнения сгруппированы по спринтам и содержат решение, команды запуска и результаты проверки.

### Проектные работы

| Спринт | Проект | Каталог |
| --- | --- | --- |
| 3 | Приложение-блог на Spring Framework | [sprint03-spring-blog](projects/sprint03-spring-blog/) |
| 4 | Перевод блога на Spring Boot | [sprint04-spring-boot-blog](projects/sprint04-spring-boot-blog/) |
| 5 | Витрина магазина на Spring Data JPA и Spring Web | [sprint05-storefront](projects/sprint05-storefront/) |
| 6 | Витрина магазина на реактивном стеке | [sprint06-reactive-storefront](projects/sprint06-reactive-storefront/) |
| 7 | RESTful-сервис, OpenAPI и Redis | [sprint07-rest-service](projects/sprint07-rest-service/) |
| 8 | Spring Security | [sprint08-security](projects/sprint08-security/) |
| 9 | Микросервисное приложение банка | [sprint09-bank](projects/sprint09-bank/) |
| 10 | Взаимодействие микросервисов через Apache Kafka | [sprint10-kafka](projects/sprint10-kafka/) |
| 11 | Логирование и мониторинг | [sprint11-observability](projects/sprint11-observability/) |

Каталоги появляются по мере выполнения работ. README каждого проекта содержит описание решения, команды сборки, запуска и тестирования.

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

Каждый проект будет содержать краткое описание требований, архитектуру, конфигурацию окружения и команды сборки, запуска и тестирования. Код и тесты размещаются в стандартных каталогах выбранного сборщика. Параметры окружения задаются отдельно; локальные пароли монтируются файлами Docker Compose secrets вне Git.

CI обнаруживает проекты и выполняет их реальные проверки; для ещё не добавленного проекта соответствующая задача пропускается. Формат сдачи определяется заданием на платформе; при необходимости отдельного репозитория или ветки инструкции приводятся в README проекта.

## 📖 Материалы

- [Курс Java Middle в Яндекс Практикуме](https://practicum.yandex.ru/middle-java/)
- [Документация Java 21](https://docs.oracle.com/en/java/javase/21/)
- [Документация Spring](https://spring.io/projects)
- [Документация Apache Kafka](https://kafka.apache.org/documentation/)

Репозиторий предназначен для демонстрации проектных работ. Условия использования материалов приведены в [COPYRIGHT.md](COPYRIGHT.md).

---

# Homework Projects for the Java Engineering Course

[Русский](#java-engineering-course) · [English](#homework-projects-for-the-java-engineering-course)

**[Projects](projects/) · [IDE exercises](exercises/) · [Build and tests](#-running-testing-and-submission)**

Sprint projects and homework applications for the [Middle Java Developer course](https://practicum.yandex.ru/middle-java/) at Yandex Practicum.

The repository follows the sprint sequence, from a Spring Framework application to microservice communication, logging, and monitoring.

## 📚 About the Course

Key learning areas:

- modern Java, concurrency, and testing;
- Spring Framework, Spring Boot, and web applications;
- data access with Spring Data JPA, the reactive stack, and Redis;
- REST APIs, OpenAPI, and Spring Security;
- microservices, Apache Kafka, logging, and monitoring.

## 📁 Repository Structure

Sprint applications use [projects/](projects/); external IDE exercises use [exercises/](exercises/). Exercises are grouped by sprint, with solutions, run commands, and verification results.

### Sprint Projects

| Sprint | Project | Directory |
| --- | --- | --- |
| 3 | Blog application with Spring Framework | [sprint03-spring-blog](projects/sprint03-spring-blog/) |
| 4 | Blog migration to Spring Boot | [sprint04-spring-boot-blog](projects/sprint04-spring-boot-blog/) |
| 5 | Storefront with Spring Data JPA and Spring Web | [sprint05-storefront](projects/sprint05-storefront/) |
| 6 | Storefront on the reactive stack | [sprint06-reactive-storefront](projects/sprint06-reactive-storefront/) |
| 7 | REST service with OpenAPI and Redis | [sprint07-rest-service](projects/sprint07-rest-service/) |
| 8 | Spring Security | [sprint08-security](projects/sprint08-security/) |
| 9 | Banking microservices | [sprint09-bank](projects/sprint09-bank/) |
| 10 | Microservice communication with Apache Kafka | [sprint10-kafka](projects/sprint10-kafka/) |
| 11 | Logging and monitoring | [sprint11-observability](projects/sprint11-observability/) |

Project directories are added with their implementations. Each project README provides its solution overview and build, run, and test commands.

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

Each project will document its requirements, architecture, environment configuration, and build, run, and test commands. Source code and tests follow the selected build tool's standard layout. Runtime settings are configured separately; local passwords are mounted from Docker Compose secret files outside Git.

CI discovers projects and runs their actual checks; jobs for projects not yet added are skipped. Submission follows the platform's assignment instructions. Any requirement for a separate repository or branch will be documented in the project's README.

## 📖 Additional Resources

- [Middle Java course at Yandex Practicum](https://practicum.yandex.ru/middle-java/)
- [Java 21 Documentation](https://docs.oracle.com/en/java/javase/21/)
- [Spring Documentation](https://spring.io/projects)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)

This repository presents coursework projects. See [COPYRIGHT.md](COPYRIGHT.md) for content usage terms.
