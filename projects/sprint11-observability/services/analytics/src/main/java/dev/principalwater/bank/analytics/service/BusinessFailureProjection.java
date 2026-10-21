package dev.principalwater.bank.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.dao.ClickHouseWarehouse;
import dev.principalwater.bank.analytics.model.InvalidAnalyticsRecord;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.BusinessFailureEvent;
import dev.principalwater.bank.messaging.MessageCodec;
import dev.principalwater.bank.messaging.MessageRejected;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class BusinessFailureProjection {
    private final MessageCodec codec;
    private final ClickHouseWarehouse warehouse;
    private final AnalyticsProjectionHandler quarantine;

    public BusinessFailureProjection(
            ObjectMapper mapper,
            Validator validator,
            ClickHouseWarehouse warehouse,
            AnalyticsProjectionHandler quarantine) {
        this.codec = new MessageCodec(mapper, validator);
        this.warehouse = warehouse;
        this.quarantine = quarantine;
    }

    @KafkaListener(
            topics = BankTopics.BUSINESS_FAILURES,
            groupId = BankTopics.BUSINESS_FAILURES_GROUP,
            autoStartup = "${bank.analytics.delivery-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        BusinessFailureEvent event;
        try {
            event = codec.decode(record.value(), BusinessFailureEvent.class);
            if (!event.ownerId().toString().equals(record.key())) throw new MessageRejected();
        } catch (MessageRejected failure) {
            quarantine.quarantineIngress(record, InvalidAnalyticsRecord.Reason.MALFORMED);
            return;
        }
        // Офсет подтверждается после durable async insert. ReplacingMergeTree устраняет повтор
        // по неизменяемому event_id, если процесс упал между flush ClickHouse и commit Kafka.
        warehouse.insertFailure(event);
    }
}
