package dev.principalwater.store;

import dev.principalwater.store.model.StoreError;
import java.time.Duration;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public abstract class ReactiveTestBase extends TestBase {
    protected static final Duration TIMEOUT = Duration.ofSeconds(15);

    protected static <T> T await(Mono<T> operation) {
        return Objects.requireNonNull(operation.block(TIMEOUT), "Expected a reactive result");
    }

    protected static void expectError(Mono<?> operation, StoreError error) {
        StepVerifier.create(operation).expectErrorSatisfies(failure -> assertStoreError(error, failure)).verify(TIMEOUT);
    }
}
