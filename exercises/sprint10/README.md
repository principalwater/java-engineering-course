# Спринт 10 — Apache Kafka

Цель — проверить обмен событиями, границы подтверждения обработки и развёртывание приложений с брокером.

| Практика | Что получилось |
| --- | --- |
| [Нативные клиенты](kafka-clients/README.md) | Три producer/consumer, batching, rebalance, replay после сбоя, транзакционный калькулятор и эксперимент с ISR |
| [Spring Kafka](spring-kafka/README.md) | Отдельные JAR, контракты событий, повторы и постоянная SQL-таблица DLQ |
| [Kafka Streams](streams/README.md) | Обогащение событий последним профилем и три сценария с Embedded Kafka |
| [Helm и Kubernetes](event-logging/README.md) | Раздельные релизы Kafka/приложения, пробы, отказ брокера и сохранность сообщений |

Все приложения используют Java 21 и общий Gradle wrapper репозитория. Нативные клиенты и Spring Kafka работают с [двухброкерным Compose-стендом](broker/compose.yml); Kubernetes-практика использует отдельный кластер. Конфигурации локальные: PLAINTEXT, без внешней публикации брокеров; для общей среды нужны TLS/SASL, ACL и отказоустойчивый controller quorum.

Проектная работа находится отдельно в `projects/`.
