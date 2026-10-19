package dev.principalwater.bank.accounts;

import dev.principalwater.bank.testing.JdbcServiceTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:bank-common.properties,classpath:bank-kafka.properties",
            "bank.analytics.enabled=true"
        })
@Import(AccountsTestBase.PreciseClock.class)
public abstract class AccountsTestBase extends JdbcServiceTestBase {
    @TestConfiguration(proxyBeanMethods = false)
    static class PreciseClock {
        @Bean
        @Primary
        Clock preciseClock() {
            return Clock.fixed(Instant.parse("2026-10-17T12:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }

    @Override
    protected void resetDatabase() {
        jdbc.execute(
                "TRUNCATE notification_inbox,bank_kafka_outbox,bank_outbox,ledger_entry,money_operation,account RESTART IDENTITY");
    }
}
