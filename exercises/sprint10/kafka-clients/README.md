# Kafka: клиенты, транзакции и ISR

Цель — проследить сообщение от подтверждённой отправки до обработки, а затем проверить, что происходит при rebalance, аварии consumer и потере реплики.

Java 21, Kafka Client/Broker 4.0.0. Два брокера работают в KRaft; один совмещён с controller. Хранилища постоянные, внешние listeners доступны только на `127.0.0.1:19591,19592`, внутренние — по Docker DNS. Один controller не обеспечивает HA.

## 1. Поднять стенд и собрать клиентов

Из этого каталога:

```bash
docker compose -p java-sprint10-kafka -f ../broker/compose.yml up -d --wait --wait-timeout 120
../../sprint02/file-transformer-gradle-plugin/gradlew --no-daemon --console=plain kafkaTest
python3 verify.py
```

Нужны JDK 21 в `JAVA_HOME`, Docker Compose и Python 3. Проверка `kafkaTest` собирает дистрибутив и создаёт топики: `kafka-mystery` — 5 партиций, `input-numbers`/`results` — по 10, служебный `ack-probe` — 1. Везде RF=2 и `min.insync.replicas=2`. Затем она временно останавливает только `broker2`, меняет ISR-порог только у `ack-probe` и восстанавливает исходное состояние. Параллельно запускать другой эксперимент на этом стенде не следует.

## 2. Отправить и прочитать сообщения

В первом терминале запустить приложение с тремя consumers:

```bash
build/install/kafka-clients/bin/kafka-clients consumer first 3 180 mystery-consumers
```

Во втором — три producer-потока с общей потокобезопасной `KafkaProducer`:

```bash
build/install/kafka-clients/bin/kafka-clients producer normal 3 10 1000 normal
build/install/kafka-clients/bin/kafka-clients producer batch 3 10 1000 batch
```

Ключ выбирается из `key1`…`key10`, значение содержит имя потока и случайное число 0…99, timestamp задаётся при отправке. `SENT` появляется после broker ack; `RECORD` содержит ключ, партицию, offset, значение и задержку от CreateTime до чтения. Один ключ сохраняет партицию при неизменном числе партиций; несколько ключей могут попасть в одну.

Режим `batch` меняет только `batch.size=131072` и `linger.ms=2000`; `normal` создаёт новый клиент с настройками по умолчанию. В проверке отправлено по 120 сообщений каждого режима, по три потока с интервалом 100 мс. На локальном macOS ARM64/JDK 21/Docker получено:

| Режим | Средняя задержка, мс | p50, мс | p95, мс |
| --- | ---: | ---: | ---: |
| Обычный | 57,4 | 21 | 244 |
| Batch | 1087,7 | 1073,5 | 2013 |

Задержка batch ожидаема при малом потоке и ожидании наполнения пакета. Это наблюдение на конечной выборке, а не универсальный benchmark; скрипт не требует от одного режима обязательного выигрыша.

## 3. Проверить группу и остановку

Ещё одно приложение с тем же group ID создаёт суммарно шесть consumers. Проверка через broker CLI подтверждает распределение `[0, 1, 1, 1, 1, 1]`: пять партиций, один свободный consumer.

У каждого потока свой `KafkaConsumer`, `poll` — до 5 секунд, автоматический commit — раз в 20 секунд. `verify.py` проверяет две разные ситуации:

- SIGTERM вызывает `wakeup`, hook ждёт закрытия клиентов их потоками; обработанные offsets сохранены до выхода JVM.
- Перед SIGKILL первый процесс приостанавливается, а broker offsets подтверждают наличие прочитанных, но ещё не закоммиченных записей. После rebalance второй процесс получает тот же `(partition, offset)` с теми же ключом и значением.

Закрытие — ограниченное по времени; аварийная обработка остаётся at-least-once и допускает повтор.

## 4. Запустить транзакционный калькулятор

```bash
build/install/kafka-clients/bin/kafka-clients calculator calculator-local-1
# В других терминалах:
build/install/kafka-clients/bin/kafka-clients read read_committed 30
build/install/kafka-clients/bin/kafka-clients read read_uncommitted 30
```

Число сначала записывается в `input-numbers` внутри открытой транзакции; затем программа запрашивает `sq` или `abs`. Результат попадает в `results`, после чего оба сообщения коммитятся. Неизвестная операция, переполнение `int` или EOF вместо операции отменяют транзакцию. Ключ — `floorMod(number, 10)`, включая отрицательные числа. Это корзина ключа, а не номер партиции: стандартный partitioner хеширует строковый ключ.

Для каждого одновременно работающего экземпляра нужен свой `transactional.id`. При timeout commit программа сообщает о неизвестном результате и не повторяет операцию автоматически. Kafka-транзакция не включает внешнюю SQL-БД.

Реальная CLI-проверка передаёт число без операции: `read_uncommitted` уже видит ввод, `read_committed` — ещё нет. После двух успешных операций и трёх отмен committed-reader получает 4 записи, uncommitted-reader — 7; служебные transaction markers в эти числа не входят.

## 5. Проверить ISR и подтверждения

При RF=2/minISR=2 и остановленном `broker2` отправка с `acks=all` отклоняется `NotEnoughReplicas`. Для отрицательного случая retries отключены, чтобы проверить broker error, а не исчерпание общего времени ожидания. После снижения minISR до 1 отправка проходит; затем второй брокер возвращается, minISR восстанавливается до 2 и проверяется ISR=2.

Каждый замер — 5 прогревочных и 20 последовательных сообщений, один producer/consumer, небольшой payload. Подтверждение измеряется монотонным таймером, доставка — от CreateTime до чтения ровно подтверждённого offset тем же JVM; p50/p95 — nearest rank:

| ISR | Ack p50/p95, мс | Доставка p50/p95, мс |
| --- | --- | --- |
| 2 | 8,627 / 9,741 | 9 / 10 |
| 1 | 7,597 / 8,553 | 8 / 9 |

`acks=all` ждёт требуемые in-sync replicas, а не произвольное фиксированное число копий. Малый локальный замер не оценивает пропускную способность production-кластера.

Отчёты: `build/reports/tests/kafkaTest/`, `build/verification/{one,two}-isr.json` и `build/verification/<run>/results.json`. Для остановки после завершения клиентов:

```bash
docker compose -p java-sprint10-kafka -f ../broker/compose.yml stop
```

Контейнеры и volumes сохраняются. Источники: [Kafka Producer Configs](https://kafka.apache.org/40/configuration/producer-configs/), [KafkaConsumer](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html), [KafkaProducer](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html).
