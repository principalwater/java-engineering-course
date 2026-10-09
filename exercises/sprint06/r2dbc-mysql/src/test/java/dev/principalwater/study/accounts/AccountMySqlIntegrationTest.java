package dev.principalwater.study.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

@SpringBootTest(classes = {AccountsApplication.class, AccountMySqlIntegrationTest.DatabaseConfiguration.class})
class AccountMySqlIntegrationTest {
    @Autowired private AccountService service;
    @Autowired private AccountRepository accounts;
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(15);

    /** Настоящий MySQL проверяет ID, точность DECIMAL, строгую границу Query Method и CHECK. */
    @Test
    void richAccountQueryExcludesTheBoundaryAndPreservesDecimal() {
        List<Account> saved = service.saveAll(new Account("Low", new BigDecimal("5000.00")),
                new Account("Boundary"), new Account("O'Коннор", new BigDecimal("25000.50")))
                .collectList().block(VERIFY_TIMEOUT);
        assertThat(saved).hasSize(3);
        try {
            assertThat(saved).extracting(Account::id).doesNotContainNull().doesNotHaveDuplicates();
            assertThat(saved).allSatisfy(account -> assertThat(account.id()).isPositive());
            StepVerifier.create(service.findRichAccounts(new BigDecimal("10000.00")))
                    .assertNext(account -> {
                        assertThat(account.name()).isEqualTo("O'Коннор");
                        assertThat(account.balance()).isEqualByComparingTo("25000.50");
                    }).expectComplete().verify(VERIFY_TIMEOUT);
            StepVerifier.create(service.saveAll(new Account("Invalid", new BigDecimal("-1.00"))))
                    .expectError(DataAccessException.class).verify(VERIFY_TIMEOUT);
            assertThat(accounts.count().block(VERIFY_TIMEOUT)).isEqualTo(3L);
        } finally {
            accounts.deleteAllById(saved.stream().map(Account::id).toList()).block(VERIFY_TIMEOUT);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection(type = R2dbcConnectionDetails.class)
        MySQLContainer<?> database() {
            // Контейнер живёт вместе с кешируемым Spring-контекстом; digest сохранён в официальном зеркале.
            return new MySQLContainer<>(DockerImageName.parse(
                    "public.ecr.aws/docker/library/mysql@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242")
                    .asCompatibleSubstituteFor("mysql"));
        }
    }
}
