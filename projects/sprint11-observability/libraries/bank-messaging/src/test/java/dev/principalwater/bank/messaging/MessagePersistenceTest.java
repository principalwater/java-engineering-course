package dev.principalwater.bank.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.UncategorizedSQLException;

class MessagePersistenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"22P05", "22001", "23502", "23514"})
    void dataAndIntegrityFailuresBecomeSafePermanentMessageRejection(String state) {
        var failure =
                new UncategorizedSQLException(
                        "fixture", "INSERT", new SQLException("private payload", state));
        assertThatThrownBy(
                        () ->
                                MessagePersistence.store(
                                        () -> {
                                            throw failure;
                                        }))
                .isInstanceOf(MessageRejected.class)
                .hasMessage("Message violates the immutable event contract")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "40001", "40P01", "55P03", "57014", "53300"})
    void connectionSerializationLockShutdownAndCapacityFailuresRemainRetriable(String state) {
        var failure =
                new UncategorizedSQLException(
                        "fixture", "INSERT", new SQLException("unavailable", state));
        assertThatThrownBy(
                        () ->
                                MessagePersistence.store(
                                        () -> {
                                            throw failure;
                                        }))
                .isSameAs(failure);
    }
}
