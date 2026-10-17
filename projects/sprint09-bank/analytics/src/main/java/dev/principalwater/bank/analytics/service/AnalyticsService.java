package dev.principalwater.bank.analytics.service;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.dao.ClickHouseWarehouse;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.OwnerReference;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.security.UserIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsService.class);
    private final ServiceClient accounts;
    private final AnalyticsInbox inbox;
    private final CassandraActivityStore cassandra;
    private final ClickHouseWarehouse warehouse;
    private final Clock clock;

    public AnalyticsService(
            ServiceClient accounts,
            AnalyticsInbox inbox,
            CassandraActivityStore cassandra,
            ClickHouseWarehouse warehouse,
            Clock clock) {
        this.accounts = accounts;
        this.inbox = inbox;
        this.cassandra = cassandra;
        this.warehouse = warehouse;
        this.clock = clock;
    }

    public AnalyticsView personal(Jwt jwt) {
        try {
            UUID owner = owner(jwt);
            YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
            if (inbox.customerPending(owner, start(month), start(month.plusMonths(1)))) {
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

    public AnalyticsView business() {
        try {
            YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
            if (inbox.warehousePending(start(month), start(month.plusMonths(1)))) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_PENDING);
            }
            return warehouse.month(month);
        } catch (AnalyticsUnavailable failure) {
            return unavailable(failure.getCategory());
        } catch (DataAccessException failure) {
            return unavailable(AnalyticsUnavailable.Category.INBOX);
        }
    }

    public void view(Jwt jwt, UUID eventId) {
        inbox.acceptView(eventId, owner(jwt));
    }

    private UUID owner(Jwt jwt) {
        OwnerReference reference =
                accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        UserIdentity.actor(jwt),
                        OwnerReference.class);
        if (reference == null || reference.ownerId() == null) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.IDENTITY);
        }
        return reference.ownerId();
    }

    private Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private AnalyticsView unavailable(AnalyticsUnavailable.Category category) {
        LOG.warn("Analytics view unavailable: {}", category);
        return AnalyticsView.unavailable();
    }
}
