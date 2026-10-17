package dev.principalwater.store.config;

import dev.principalwater.store.ReactiveTestBase;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisLoadingException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTest extends ReactiveTestBase {
    @Test
    void unavailableStorageInFiltersAnswersServiceUnavailableWithoutHandingOutASessionCookie() {
        for (Throwable failure : new Throwable[] {new RedisConnectionFailureException("session store is down"),
                new IllegalStateException("wrapped", new QueryTimeoutException("command timeout")),
                new RedisCommandTimeoutException("HGETALL. Command timed out"), new RedisConnectionException("refused"),
                new RedisSystemException("Redis exception", new RedisException("Currently not connected. Commands are rejected.")),
                new RedisSystemException("Error in execution", new RedisLoadingException("LOADING Redis is loading the dataset")),
                new TimeoutException("deadline")}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/cabinet"));
            exchange.getResponse().addCookie(ResponseCookie.from("SESSION", "never-stored").build());

            StepVerifier.create(new WebConfig().storageUnavailable().handle(exchange, failure)).verifyComplete();

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            assertEquals("1", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            assertTrue(exchange.getResponse().getCookies().isEmpty(), "A session missing from the store must not be handed to the client");
        }
    }

    @Test
    void failuresThatAreNotAnOutageKeepTheirOwnMeaning() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/cabinet"));
        exchange.getResponse().addCookie(ResponseCookie.from("SESSION", "kept").build());
        for (Throwable failure : new Throwable[] {new DataIntegrityViolationException("duplicate"),
                new RedisCommandExecutionException("NOPERM this user has no permissions to run the 'hmset' command"),
                new RedisSystemException("Error in execution", new RedisCommandExecutionException("NOPERM"))}) {
            StepVerifier.create(new WebConfig().storageUnavailable().handle(exchange, failure))
                    .expectErrorMatches(error -> error == failure).verify(TIMEOUT);
        }

        assertFalse(exchange.getResponse().getCookies().isEmpty());
        assertEquals(null, exchange.getResponse().getStatusCode());
    }
}
