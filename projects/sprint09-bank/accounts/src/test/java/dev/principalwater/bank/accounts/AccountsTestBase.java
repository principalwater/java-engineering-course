package dev.principalwater.bank.accounts;

import dev.principalwater.bank.testing.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:bank-common.properties,classpath:bank-service-client.properties",
            "spring.cloud.zookeeper.enabled=false",
            "spring.cloud.zookeeper.config.enabled=false",
            "spring.cloud.zookeeper.discovery.enabled=false",
            "bank.delivery.enabled=false",
            "spring.security.oauth2.client.registration.service.client-secret=fixture-secret",
            "bank.analytics.enabled=true"
        })
@AutoConfigureMockMvc
@Import(AccountsTestBase.PreciseClock.class)
public abstract class AccountsTestBase extends PostgresTestBase {
    @TestConfiguration(proxyBeanMethods = false)
    static class PreciseClock {
        @Bean
        @Primary
        Clock preciseClock() {
            return Clock.fixed(Instant.parse("2026-10-17T12:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE bank_outbox,ledger_entry,money_operation,account RESTART IDENTITY");
    }
}
