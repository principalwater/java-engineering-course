# Kafka и приложение в отдельных Helm-релизах

Цель — развернуть брокер независимо от приложения и проверить обмен сообщениями, пробы и сохранность данных при пересоздании Pod.

Java 21, Spring Boot 3.5.8, Kafka 4.0.0, Helm 4.3.1, kind 0.33.0/Kubernetes 1.34.11. Локальная проверка выполнена с kubectl 1.33.9; CI использует 1.34.11. Образы закреплены digest; tag приложения вычисляется из SHA-256 собранного JAR.

## 1. Подготовить отдельный кластер

Из этого каталога:

```bash
export KUBECONFIG="$HOME/Documents/study/java-runtime/sprint10/kubeconfig"
mkdir -p "$(dirname "$KUBECONFIG")"
docker network create --subnet 10.251.10.32/27 java-sprint10-kind
KIND_EXPERIMENTAL_DOCKER_NETWORK=java-sprint10-kind kind create cluster \
  --name java-sprint10 --config kind.yml --kubeconfig "$KUBECONFIG" --wait 180s
chmod 600 "$KUBECONFIG"
```

Это первоначальное создание: существующий кластер и сеть повторно создавать не нужно. Диапазон сети должен быть свободен; переопределение сети kind — экспериментальная возможность, использованная из-за занятых Docker address pools. API слушает loopback, kubeconfig хранится вне репозитория. Все последующие команды требуют этот файл и проверяют контекст `kind-java-sprint10`.

## 2. Собрать и установить два релиза

```bash
../../sprint02/file-transformer-gradle-plugin/gradlew --no-daemon --console=plain bootJar
bash deploy.sh
helm --kubeconfig "$KUBECONFIG" -n java-sprint10 list
kubectl --kubeconfig "$KUBECONFIG" -n java-sprint10 get pods,pvc
```

`deploy.sh` собирает образ из проверенного JAR, загружает его в kind, выполняет `helm lint/template` и устанавливает отдельно `kafka` и `event-logging`. Вместо внешнего чарта используется небольшой собственный: один Kafka broker/controller, StatefulSet, PVC и идемпотентный hook создания `log-events`. Это допустимая альтернатива чарта упражнения без зависимости от чужого registry. Имена ресурсов фиксированы для одного экземпляра в выделенном namespace; HA, общая среда и несколько экземпляров чарта потребуют другого quorum, RF, TLS/SASL/ACL и расширения шаблонов.

Приложение раз в секунду отправляет сообщение с UUID-ключом и слушает тот же топик. Логи `EVENT_SENT` появляются после broker ack; `EVENT_RECEIVED` позволяют сопоставить UUID, partition и offset. Планировщик включён явно; ожидание ack ограничивает незавершённые отправки. Подтверждение consumer — после listener (`AckMode.RECORD`).

Приложение работает от непривилегированного UID, без service account token и записи в root filesystem; временные файлы — в ограниченном `emptyDir`. Broker хранит данные на PVC. Service — ClusterIP, публичного ingress нет.

## 3. Разделить пробы

Startup и liveness проверяют `/actuator/health/liveness`; readiness — `/actuator/health/readiness`, включая ограниченный по времени Admin-запрос к Kafka. Недоступный брокер исключает приложение из готовых endpoints, но не вызывает цикл перезапусков исправного JVM. Это намеренное отличие от одинакового `/actuator/health` для всех трёх проб в примере задания. Приложение слушает непривилегированный порт 8080.

## 4. Проверить сбой и сохранность

```bash
python3 verify.py
```

Проверка работает только в выделенном контексте и namespace. Сначала она сопоставляет минимум три подтверждённых и прочитанных события. Затем масштабирует только Kafka StatefulSet до нуля: readiness приложения становится `503`, liveness остаётся `200`, UID и restart count приложения не меняются. В `finally` брокер возвращается.

После нового Kafka Pod сохраняются UID PVC и ID топика. Старое подтверждённое сообщение читается по исходному offset, новые UUID снова проходят полный путь. Локально было сопоставлено 22 сообщения до сбоя и 26 после восстановления. Результат — `target/verification/results.json`; логи и временный loopback port-forward `19593` принадлежат только этому прогону. Это локальная одноброкерная проверка, не доказательство HA.

## 5. Остановить стенд

```bash
kubectl --kubeconfig "$KUBECONFIG" -n java-sprint10 scale deployment/event-logging --replicas=0
kubectl --kubeconfig "$KUBECONFIG" -n java-sprint10 scale statefulset/kafka --replicas=0
kubectl --kubeconfig "$KUBECONFIG" -n java-sprint10 wait --for=delete pod/kafka-0 --timeout=90s
docker stop java-sprint10-control-plane
```

Кластер, Helm-релизы, PVC и Docker volume сохраняются. Для продолжения — `docker start java-sprint10-control-plane`, дождаться Ready узла и повторить `bash deploy.sh` с тем же kubeconfig. Для осознанного удаления релизов есть `helm uninstall event-logging kafka -n java-sprint10 --kubeconfig "$KUBECONFIG"`; PVC имеет отдельный жизненный цикл и автоматически не удаляется.

Источники: [Helm charts](https://helm.sh/docs/topics/charts/), [Kubernetes probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/), [kind 0.33.0](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0).
