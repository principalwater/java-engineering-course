package dev.principalwater.bank.analytics.service;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class PersonalAnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalAnalyticsService.class);
    private final AnalyticsIdentity identity;
    private final AnalyticsInbox inbox;
    private final CassandraActivityStore cassandra;
    private final AnalyticsProjectionHandler handler;
    private final Clock clock;

    public PersonalAnalyticsService(
            AnalyticsIdentity identity,
            AnalyticsInbox inbox,
            CassandraActivityStore cassandra,
            AnalyticsProjectionHandler handler,
            Clock clock) {
        this.identity = identity;
        this.inbox = inbox;
        this.cassandra = cassandra;
        this.handler = handler;
        this.clock = clock;
    }

    public AnalyticsView personal(Jwt jwt) {
        try {
            UUID owner = identity.owner(jwt);
            YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
            if (inbox.customerPending(
                    owner,
                    month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_PENDING);
            }
            return ActivitySummary.personal(cassandra.month(owner, month));
        } catch (AnalyticsUnavailable failure) {
            return unavailable(failure.getCategory());
        } catch (RemoteUnavailable failure) {
            return unavailable(AnalyticsUnavailable.Category.IDENTITY);
        } catch (DataAccessException failure) {
            return unavailable(AnalyticsUnavailable.Category.INBOX);
        }
    }

    public void view(Jwt jwt, UUID eventId) {
        inbox.acceptView(eventId, identity.owner(jwt));
    }

    @KafkaListener(
            id = AnalyticsProjection.PERSONAL_GROUP,
            groupId = AnalyticsProjection.PERSONAL_GROUP,
            topics = AnalyticsProjection.TOPIC,
            autoStartup = "${bank.analytics.delivery-enabled:true}")
    public void project(ConsumerRecord<String, String> record) {
        handler.deliver(record, AnalyticsProjection.PERSONAL, cassandra::insert);
    }

    private AnalyticsView unavailable(AnalyticsUnavailable.Category category) {
        LOG.warn("Personal analytics unavailable: {}", category);
        return AnalyticsView.unavailable();
    }
}
