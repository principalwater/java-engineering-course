package dev.principalwater.store.util;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisLoadingException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

public final class StorageFailures {
    private StorageFailures() {
    }

    public static boolean isUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            // Lettuce отдаёт таймаут/обрыв напрямую или внутри RedisSystemException; NOPERM/WRONGTYPE — другие ошибки.
            if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessException
                    || cause instanceof TimeoutException || cause instanceof RedisLoadingException
                    || cause instanceof RedisException && !(cause instanceof RedisCommandExecutionException)) {
                return true;
            }
        }
        return false;
    }
}
