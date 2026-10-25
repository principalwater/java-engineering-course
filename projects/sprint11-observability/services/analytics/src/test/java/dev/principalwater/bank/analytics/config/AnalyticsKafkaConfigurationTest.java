package dev.principalwater.bank.analytics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.security.JaasContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;

class AnalyticsKafkaConfigurationTest {
    @TempDir Path secrets;

    @Test
    void nativeSecretAndStandardSpringTlsPropertiesReachEveryAnalyticsClient() throws Exception {
        Files.writeString(secrets.resolve("bank.kafka.password"), "fixture-password\n");
        Path authority = secrets.resolve("ca.crt");
        Files.writeString(authority, "fixture-public-ca");
        new ApplicationContextRunner()
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "systemEnvironment",
                                                        Map.of(
                                                                "BANK_CONFIG_IMPORT",
                                                                "configtree:" + secrets + "/",
                                                                "BANK_KAFKA_USER",
                                                                "analytics-service",
                                                                "SPRING_KAFKA_SECURITY_PROTOCOL",
                                                                "SASL_SSL",
                                                                "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM",
                                                                "SCRAM-SHA-512",
                                                                "SPRING_KAFKA_SSL_TRUSTSTORETYPE",
                                                                "PEM",
                                                                "SPRING_KAFKA_SSL_TRUSTSTORELOCATION",
                                                                authority.toUri().toString()))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(
                        AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class,
                                KafkaAutoConfiguration.class))
                .withUserConfiguration(AnalyticsKafka.class)
                .withPropertyValues(
                        "spring.config.location=classpath:application.properties",
                        "spring.config.import=configtree:" + secrets + "/",
                        "spring.cloud.zookeeper.enabled=false",
                        "spring.cloud.zookeeper.config.enabled=false",
                        "spring.cloud.zookeeper.discovery.enabled=false")
                .run(
                        context -> {
                            Map<?, ?> producer =
                                    context.getBean(DefaultKafkaProducerFactory.class)
                                            .getConfigurationProperties();
                            Map<?, ?> consumer =
                                    context.getBean(DefaultKafkaConsumerFactory.class)
                                            .getConfigurationProperties();
                            Map<?, ?> admin =
                                    context.getBean(KafkaAdmin.class).getConfigurationProperties();
                            for (Map<?, ?> settings :
                                    java.util.List.of(producer, consumer, admin)) {
                                assertEquals("SASL_SSL", settings.get("security.protocol"));
                                assertEquals("SCRAM-SHA-512", settings.get("sasl.mechanism"));
                                assertEquals("PEM", settings.get("ssl.truststore.type"));
                                assertEquals(
                                        authority.toString(),
                                        settings.get("ssl.truststore.location"));
                                assertNotNull(
                                        settings.get("sasl.jaas.config"),
                                        "Every Kafka client must authenticate with its service principal");
                                var login =
                                        JaasContext.loadClientContext(
                                                        Map.of(
                                                                "sasl.jaas.config",
                                                                new Password(
                                                                        settings.get(
                                                                                        "sasl.jaas.config")
                                                                                .toString())))
                                                .configurationEntries()
                                                .getFirst();
                                assertEquals(
                                        "analytics-service", login.getOptions().get("username"));
                                assertEquals(
                                        "fixture-password", login.getOptions().get("password"));
                            }
                        });
    }
}
