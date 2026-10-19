package dev.principalwater.bank.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.security.JaasContext;
import org.junit.jupiter.api.Test;

class KafkaClientPropertiesTest {
    @Test
    void realJaasParserPreservesStrimziPrincipalAndQuotedPasswordAsSingleLogin() {
        String password = "operator-generated \\\" value; other=unsafe";
        var secured =
                KafkaClientProperties.secured(
                        Map.of("security.protocol", "SASL_SSL", "sasl.mechanism", "SCRAM-SHA-512"),
                        "notifications-service",
                        password);
        secured.put("sasl.jaas.config", new Password(secured.get("sasl.jaas.config").toString()));
        var entries = JaasContext.loadClientContext(secured).configurationEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getLoginModuleName())
                .isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule");
        assertThat(entries.getFirst().getOptions().get("username"))
                .isEqualTo("notifications-service");
        assertThat(entries.getFirst().getOptions().get("password")).isEqualTo(password);
        assertThat(secured.get("security.protocol")).isEqualTo("SASL_SSL");
    }

    @Test
    void controlCharactersCannotInjectJaasConfiguration() {
        assertThatThrownBy(
                        () ->
                                KafkaClientProperties.secured(
                                        Map.of(), "cash-service", "secret\nmodule"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Kafka credentials");
    }
}
