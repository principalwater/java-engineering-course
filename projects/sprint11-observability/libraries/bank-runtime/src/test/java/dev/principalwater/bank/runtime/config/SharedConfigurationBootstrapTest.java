package dev.principalwater.bank.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SharedConfigurationBootstrapTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(BankRuntimeConfiguration.class);

    @Test
    void enabledConfigCannotStartBeforeTheSharedRevisionExists() {
        // ZooKeeper принимает соединения раньше, чем отдельный Job успевает записать конфигурацию.
        context.run(
                result -> {
                    assertThat(result).hasFailed();
                    assertThat(result.getStartupFailure())
                            .hasStackTraceContaining("bank.config.revision");
                });
        context.withPropertyValues("bank.config.revision=sprint11-v1")
                .run(
                        result ->
                                assertThat(result)
                                        .hasNotFailed()
                                        .hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void explicitOfflineConfigurationNeedsNoZooKeeperRevision() {
        context.withPropertyValues("spring.cloud.zookeeper.config.enabled=false")
                .run(
                        result ->
                                assertThat(result)
                                        .hasNotFailed()
                                        .doesNotHaveBean(ApplicationRunner.class));
    }
}
