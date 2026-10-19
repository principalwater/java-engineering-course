package dev.principalwater.bank.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.runtime.client.ServiceClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bank.workflow.enabled", havingValue = "true")
public class WorkflowConfiguration {
    @Bean
    IntentRepository intentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new IntentRepository(jdbc, mapper);
    }

    @Bean
    MoneyWorkflow moneyWorkflow(
            IntentRepository intents,
            ServiceClient client,
            KafkaOutbox outbox,
            PlatformTransactionManager transactions,
            Clock clock) {
        return new MoneyWorkflow(
                intents, client, outbox, new TransactionTemplate(transactions), clock);
    }
}
