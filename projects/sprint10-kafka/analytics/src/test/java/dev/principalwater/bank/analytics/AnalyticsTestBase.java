package dev.principalwater.bank.analytics;

import static org.mockito.Mockito.when;

import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.dao.CassandraUiActivityStore;
import dev.principalwater.bank.analytics.dao.ClickHouseActivityMart;
import dev.principalwater.bank.analytics.dao.ClickHouseWarehouse;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.OwnerReference;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.testing.JdbcServiceTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:bank-common.properties,classpath:bank-service-client.properties",
            "bank.analytics.delivery-enabled=false",
            "bank.kafka.password=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "spring.security.oauth2.client.registration.service.client-secret=fixture-secret",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.cassandra.CassandraRepositoriesAutoConfiguration"
        })
@Import(AnalyticsTestBase.FixedTime.class)
public abstract class AnalyticsTestBase extends JdbcServiceTestBase {
    protected static final Instant NOW = Instant.parse("2026-10-17T01:02:03.123456789Z");
    protected static final UUID ALICE = UUID.fromString("ee486035-62dd-41d8-af0c-382f60d1c068");
    protected static final UUID BOB = UUID.fromString("e89ee362-5ca9-4064-a97b-24abe2fa4109");
    @MockitoBean protected CassandraActivityStore cassandra;
    @MockitoBean protected ClickHouseWarehouse warehouse;
    @MockitoBean protected CassandraUiActivityStore uiActivity;
    @MockitoBean protected ClickHouseActivityMart activityMart;
    @MockitoBean protected ServiceClient accounts;

    @Override
    protected void resetDatabase() {
        jdbc.execute(
                "TRUNCATE analytics_inbox, analytics_quarantine, analytics_activity_exports, analytics_ui_months");
        when(accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        new Actor(JWT.issuer(), "alice-id", "alice"),
                        OwnerReference.class))
                .thenReturn(new OwnerReference(ALICE));
        when(accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        new Actor(JWT.issuer(), "bob-id", "bob"),
                        OwnerReference.class))
                .thenReturn(new OwnerReference(BOB));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock analyticsTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
