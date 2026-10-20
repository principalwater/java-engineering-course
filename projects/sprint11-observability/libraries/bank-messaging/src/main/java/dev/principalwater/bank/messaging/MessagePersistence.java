package dev.principalwater.bank.messaging;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

// Повтор доставки не исправляет ошибки данных и ограничений, но позволяет пережить
// временные сбои сети, блокировки и остановку сервиса.
public final class MessagePersistence {
    private MessagePersistence() {}

    public static void store(Runnable action) {
        try {
            action.run();
        } catch (DataAccessException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("22") || state.startsWith("23")))
                    throw new MessageRejected();
            }
            throw failure;
        }
    }
}
