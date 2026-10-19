package dev.principalwater.bank.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsRecord;
import dev.principalwater.bank.analytics.model.InvalidAnalyticsRecord;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsProjectionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsProjectionHandler.class);
    // Фиксированный BankEvent меньше 1KiB; 4KiB оставляют запас ниже broker limit 32KiB и
    // ограничивают JSON parsing.
    private static final int MAX_EVENT_CHARACTERS = 4096;
    private final AnalyticsInbox inbox;
    private final ObjectMapper mapper;

    public AnalyticsProjectionHandler(AnalyticsInbox inbox, ObjectMapper mapper) {
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void deliver(
            ConsumerRecord<String, String> record,
            AnalyticsProjection projection,
            Consumer<BankEvent> sink) {
        deliver(record, projection, sink, null);
    }

    public void deliver(
            ConsumerRecord<String, String> record,
            AnalyticsProjection projection,
            Consumer<BankEvent> sink,
            Consumer<UiActivityEvent> uiSink) {
        AnalyticsRecord event;
        try {
            event = decode(record);
            if (!inbox.projectionPending(event, projection)) {
                return;
            }
        } catch (InvalidAnalyticsRecord failure) {
            // Только durable quarantine разрешает offset commit; отказ PostgreSQL оставляет запись
            // на retry.
            inbox.quarantine(
                    projection.name(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    fingerprint(record),
                    failure.getReason());
            LOG.warn(
                    "Analytics record quarantined: {} {}",
                    projection.getGroupId(),
                    failure.getReason());
            return;
        }
        if (event.ui() != null) {
            // UI warehouse ACK принадлежит Cassandra-derived batch. Offset commit не теряет задачу:
            // PG хранит её до ACK.
            if (projection == AnalyticsProjection.BUSINESS) return;
            uiSink.accept(event.ui());
        } else {
            sink.accept(event.bank());
        }
        inbox.acknowledgeProjection(event.eventId(), projection);
    }

    private AnalyticsRecord decode(ConsumerRecord<String, String> record) {
        if (!AnalyticsProjection.TOPIC.equals(record.topic())
                || record.value() == null
                || record.value().length() > MAX_EVENT_CHARACTERS) {
            throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.MALFORMED);
        }
        try {
            AnalyticsRecord event = AnalyticsRecord.decode(record.value(), mapper);
            if (event == null || event.eventId() == null || event.ownerId() == null) {
                throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.MALFORMED);
            }
            if (!event.ownerId().toString().equals(record.key())) {
                throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.KEY_MISMATCH);
            }
            return event;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.MALFORMED);
        }
    }

    public void quarantineIngress(
            ConsumerRecord<String, String> record, InvalidAnalyticsRecord.Reason reason) {
        inbox.quarantine(
                "INGRESS",
                record.topic(),
                record.partition(),
                record.offset(),
                fingerprint(record),
                reason);
        LOG.warn("Analytics ingress record quarantined: {}", reason);
    }

    private String fingerprint(ConsumerRecord<String, String> record) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key =
                    record.key() == null
                            ? new byte[0]
                            : record.key().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(key.length).array());
            digest.update(key);
            if (record.value() != null) {
                digest.update(record.value().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
