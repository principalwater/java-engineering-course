package dev.principalwater.bank.messaging.replay;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Отдельный non-web launcher загружает только JDBC/Flyway, без scan HTTP-приложения и Kafka
// listeners.
@SpringBootConfiguration
@EnableAutoConfiguration
public class OutboxReplayApplication {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxReplayApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OutboxReplayApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Эти defaults нужны до ConfigData: профиль из application.properties читается позже
        // импортов.
        application.setDefaultProperties(
                Map.of(
                        "BANK_CONFIG_IMPORT",
                        "optional:classpath:bank-common.properties",
                        "spring.cloud.zookeeper.enabled",
                        false,
                        "spring.cloud.zookeeper.config.enabled",
                        false,
                        "spring.cloud.zookeeper.discovery.enabled",
                        false));
        application.setAdditionalProfiles("outbox-replay");
        try (var context = application.run(args)) {
            var settings = context.getEnvironment();
            OutboxReplay replay =
                    new OutboxReplay(
                            context.getBean(JdbcTemplate.class),
                            new TransactionTemplate(
                                    context.getBean(PlatformTransactionManager.class)));
            OutboxReplay.Page page =
                    replay.requeue(
                            settings.getRequiredProperty("spring.application.name"),
                            settings.getProperty("bank.outbox.replay.after", Long.class, 0L),
                            settings.getProperty("bank.outbox.replay.limit", Integer.class, 1000));
            LOG.info(
                    "Outbox replay queued count={} requeued={} nextSequence={}",
                    page.count(),
                    page.requeued(),
                    page.nextSequence());
        }
    }
}
