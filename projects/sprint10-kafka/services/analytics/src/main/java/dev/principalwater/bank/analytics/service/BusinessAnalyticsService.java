package dev.principalwater.bank.analytics.service;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.dao.ClickHouseWarehouse;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class BusinessAnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessAnalyticsService.class);
    private final AnalyticsInbox inbox;
    private final ClickHouseWarehouse warehouse;
    private final AnalyticsProjectionHandler handler;
    private final Clock clock;

    public BusinessAnalyticsService(
            AnalyticsInbox inbox,
            ClickHouseWarehouse warehouse,
            AnalyticsProjectionHandler handler,
            Clock clock) {
        this.inbox = inbox;
        this.warehouse = warehouse;
        this.handler = handler;
        this.clock = clock;
    }

    public AnalyticsView business() {
        try {
            YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
            if (inbox.warehousePending(
                    month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_PENDING);
            }
            return warehouse.month(month);
        } catch (AnalyticsUnavailable failure) {
            return unavailable(failure.getCategory());
        } catch (DataAccessException failure) {
            return unavailable(AnalyticsUnavailable.Category.INBOX);
        }
    }

    @KafkaListener(
            id = AnalyticsProjection.BUSINESS_GROUP,
            groupId = AnalyticsProjection.BUSINESS_GROUP,
            topics = AnalyticsProjection.TOPIC,
            autoStartup = "${bank.analytics.delivery-enabled:true}")
    public void project(ConsumerRecord<String, String> record) {
        handler.deliver(record, AnalyticsProjection.BUSINESS, warehouse::insert);
    }

    private AnalyticsView unavailable(AnalyticsUnavailable.Category category) {
        LOG.warn("Business analytics unavailable: {}", category);
        return AnalyticsView.unavailable();
    }
}
