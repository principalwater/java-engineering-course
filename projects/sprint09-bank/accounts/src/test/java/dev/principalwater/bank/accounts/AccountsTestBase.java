package dev.principalwater.bank.accounts;

import dev.principalwater.bank.testing.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
public abstract class AccountsTestBase extends PostgresTestBase {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE bank_outbox,ledger_entry,money_operation,account RESTART IDENTITY");
    }
}
