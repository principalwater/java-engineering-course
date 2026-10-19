package dev.principalwater.bank.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.InvalidAnalyticsRecord;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.runtime.error.BankException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class AccountsAnalyticsIngress {
    // Типизированное событие меньше 1KiB; запас 4KiB ограничивает парсер ниже broker limit.
    private static final int MAX_EVENT_CHARACTERS = 4096;
    private final AnalyticsInbox inbox;
    private final AnalyticsProjectionHandler handler;
    private final ObjectReader reader;

    public AccountsAnalyticsIngress(
            AnalyticsInbox inbox, AnalyticsProjectionHandler handler, ObjectMapper mapper) {
        this.inbox = inbox;
        this.handler = handler;
        reader =
                mapper.readerFor(BankEvent.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @KafkaListener(
            id = AnalyticsProjection.INGRESS_GROUP,
            groupId = AnalyticsProjection.INGRESS_GROUP,
            topics = AnalyticsProjection.ACCOUNTS_TOPIC,
            autoStartup = "${bank.analytics.delivery-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            if (!AnalyticsProjection.ACCOUNTS_TOPIC.equals(record.topic())
                    || record.value() == null
                    || record.value().length() > MAX_EVENT_CHARACTERS) {
                throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.MALFORMED);
            }
            BankEvent event = reader.readValue(record.value());
            if (event == null
                    || event.ownerId() == null
                    || !event.ownerId().toString().equals(record.key())) {
                throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.KEY_MISMATCH);
            }
            // Возврат разрешает offset commit только после PostgreSQL commit; Kafka ACL
            // ограничивает источник Accounts.
            inbox.acceptBackend(event);
        } catch (InvalidAnalyticsRecord failure) {
            handler.quarantineIngress(record, failure.getReason());
        } catch (BankException failure) {
            handler.quarantineIngress(record, InvalidAnalyticsRecord.Reason.PAYLOAD_MISMATCH);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            handler.quarantineIngress(record, InvalidAnalyticsRecord.Reason.MALFORMED);
        }
    }
}
