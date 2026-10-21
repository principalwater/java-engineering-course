package dev.principalwater.bank.analyticsdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Отдельный явный launcher не сканируется AnalyticsApplication и не запускает HTTP или consumers.
@SpringBootConfiguration
@EnableAutoConfiguration
public class AnalyticsDemoApplication {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsDemoApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AnalyticsDemoApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(
                Map.of(
                        "BANK_CONFIG_IMPORT", "optional:classpath:bank-common.properties",
                        "spring.cloud.zookeeper.enabled", false,
                        "spring.cloud.zookeeper.config.enabled", false,
                        "spring.cloud.zookeeper.discovery.enabled", false));
        application.setAdditionalProfiles("analytics-demo");
        try (var context = application.run(args)) {
            var settings = context.getEnvironment();
            String namespace = namespace(settings);
            AnalyticsDemo demo =
                    new AnalyticsDemo(
                            context.getBean(JdbcTemplate.class),
                            context.getBean(ObjectMapper.class),
                            new TransactionTemplate(
                                    context.getBean(PlatformTransactionManager.class)),
                            Clock.systemUTC());
            int count =
                    demo.load(
                            settings.getProperty(
                                    "bank.analytics.demo.enabled", Boolean.class, false),
                            namespace,
                            LocalDate.parse(
                                    settings.getRequiredProperty("bank.analytics.demo.end-date")));
            LOG.info(
                    "DEMO analytics queued count={} source=synthetic-demo; no ledger changes",
                    count);
        }
    }

    public static String namespace(Environment settings) {
        if (Arrays.stream(settings.getActiveProfiles())
                        .anyMatch(profile -> profile.equals("prod") || profile.equals("production"))
                || !settings.getProperty("spring.cloud.vault.enabled", Boolean.class, false)
                || !"KUBERNETES".equals(settings.getProperty("spring.cloud.vault.authentication")))
            throw new IllegalArgumentException(
                    "Analytics demo requires non-production Kubernetes Vault authentication");
        String role = settings.getRequiredProperty("spring.cloud.vault.kubernetes.role");
        if (!role.matches("bank11-(dev|test)-analytics"))
            throw new IllegalArgumentException("Analytics demo Vault role is not allowed");
        String namespace = role.substring(0, role.length() - "-analytics".length());
        // Тот же обязательный ConfigData import получает credentials: свободная namespace-опция их
        // не выбирает.
        if (!("vault:///kv/" + namespace + "/analytics")
                .equals(settings.getProperty("BANK_CONFIG_IMPORT")))
            throw new IllegalArgumentException(
                    "Analytics demo requires the matching explicit Vault KV import");
        return namespace;
    }
}
