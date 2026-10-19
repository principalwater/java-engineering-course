package dev.principalwater.bank.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.runtime.delivery.DeliveryPolicy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaOutbox implements RecoverableDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaOutbox.class);
    // Ожидание метаданных до 1 с и доставки до 4 с укладывается в аренду записи на 30 с.
    private static final int SEND_SECONDS = 5;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;

    public KafkaOutbox(
            JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.kafka = kafka;
    }

    public void enqueue(String topic, String key, String identity, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid event payload");
        }
        // Первая запись неизменяема; повтор с другим содержимым не может заменить бизнес-факт.
        jdbc.update(
                "INSERT INTO bank_kafka_outbox(id,topic,message_key,message_id,payload) VALUES (?,?,?,?,?::jsonb) ON CONFLICT(topic,message_id) DO NOTHING",
                UUID.randomUUID(),
                topic,
                key,
                identity,
                json);
        Boolean same =
                jdbc.queryForObject(
                        "SELECT message_key=? AND payload=?::jsonb FROM bank_kafka_outbox WHERE topic=? AND message_id=?",
                        Boolean.class,
                        key,
                        json,
                        topic,
                        identity);
        if (!Boolean.TRUE.equals(same))
            throw new IllegalStateException("Outbox identity belongs to different payload");
    }

    @Override
    public List<UUID> pendingIds() {
        return jdbc.query(
                "SELECT id FROM bank_kafka_outbox WHERE status='PENDING' AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_attempt_at,id LIMIT ?",
                (rs, n) -> rs.getObject(1, UUID.class),
                DeliveryPolicy.BATCH_SIZE);
    }

    @Override
    public void recover(UUID id) {
        UUID lease = UUID.randomUUID();
        List<Message> rows =
                jdbc.query(
                        "UPDATE bank_kafka_outbox SET lease_token=?,lease_until=now()+make_interval(secs=>?) WHERE id=? AND status='PENDING' AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) RETURNING topic,message_key,payload::text",
                        (rs, n) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3)),
                        lease,
                        DeliveryPolicy.LEASE_SECONDS,
                        id);
        if (rows.isEmpty()) return;
        Message message = rows.getFirst();
        try {
            // Сетевое ожидание начинается после закрытия JDBC claim; ACK означает только
            // публикацию.
            kafka.send(message.topic(), message.key(), message.payload())
                    .get(SEND_SECONDS, TimeUnit.SECONDS);
            jdbc.update(
                    "UPDATE bank_kafka_outbox SET status='PUBLISHED',lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND status='PENDING'",
                    id,
                    lease);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            defer(id, lease, failure);
        } catch (Exception failure) {
            defer(id, lease, failure);
        }
    }

    private void defer(UUID id, UUID lease, Exception failure) {
        jdbc.update(
                "UPDATE bank_kafka_outbox SET lease_token=NULL,lease_until=NULL,next_attempt_at=now()+make_interval(secs=>?),attempts=least(attempts+1,1000000) WHERE id=? AND lease_token=? AND status='PENDING'",
                DeliveryPolicy.RETRY_DELAY_SECONDS,
                id,
                lease);
        LOG.warn("Kafka outbox unavailable: {}", failure.getClass().getSimpleName());
    }

    private record Message(String topic, String key, String payload) {}
}
