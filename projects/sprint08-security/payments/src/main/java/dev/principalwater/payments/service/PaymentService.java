package dev.principalwater.payments.service;

import dev.principalwater.payments.config.PaymentProperties;
import dev.principalwater.payments.dao.PaymentRepository;
import dev.principalwater.payments.model.DebitCommand;
import dev.principalwater.payments.model.PaymentDecision;
import dev.principalwater.payments.model.PaymentError;
import dev.principalwater.payments.model.PaymentException;
import dev.principalwater.payments.model.PaymentResult;
import dev.principalwater.payments.model.PaymentStatus;
import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
public class PaymentService {
    private static final int CREATION_RACE_RETRIES = 2;
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);
    private final PaymentRepository repository;
    private final PaymentProperties properties;
    private final TransactionalOperator transactions;

    public PaymentService(PaymentRepository repository, PaymentProperties properties, TransactionalOperator transactions) {
        this.repository = repository;
        this.properties = properties;
        this.transactions = transactions;
    }

    public Mono<Long> balance(UUID accountId) {
        return repository.balance(accountId).defaultIfEmpty(properties.getOpeningBalanceMinor()).timeout(OPERATION_TIMEOUT);
    }

    public Mono<PaymentResult> debit(UUID key, DebitCommand command) {
        if (key == null || command == null) {
            return Mono.error(new PaymentException(PaymentError.INVALID_REQUEST));
        }
        return Mono.defer(() -> repository.decision(key).map(previous -> replay(previous, command))
                .switchIfEmpty(Mono.defer(() -> transact(key, command))))
                // Повторяем только транзакцию с известным rollback после гонки UNIQUE, а не сетевой unknown outcome.
                .retryWhen(Retry.max(CREATION_RACE_RETRIES).filter(DuplicateKeyException.class::isInstance)
                        .onRetryExhaustedThrow((retry, signal) -> new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT)))
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<PaymentResult> transact(UUID key, DebitCommand command) {
        return repository.createWallet(command.accountId(), properties.getOpeningBalanceMinor())
                .then(repository.lockWallet(command.accountId()))
                .switchIfEmpty(Mono.error(new PaymentException(PaymentError.UNAVAILABLE)))
                .flatMap(balance -> repository.decision(key).map(previous -> replay(previous, command))
                        .switchIfEmpty(repository.reference(command.accountId(), command.referenceId())
                                .flatMap(previous -> Mono.<PaymentResult>error(new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT)))
                                .switchIfEmpty(Mono.defer(() -> decide(key, command, balance)))))
                .as(transactions::transactional);
    }

    private Mono<PaymentResult> decide(UUID key, DebitCommand command, long balance) {
        boolean succeeds = balance >= command.amountMinor();
        var decision = new PaymentDecision(key, command, succeeds ? Math.subtractExact(balance, command.amountMinor()) : balance,
                succeeds ? PaymentStatus.SUCCEEDED : PaymentStatus.DECLINED);
        return repository.save(decision).then(succeeds ? repository.debit(decision, balance) : Mono.empty())
                .thenReturn(new PaymentResult(decision, false));
    }

    private PaymentResult replay(PaymentDecision previous, DebitCommand command) {
        if (!previous.command().equals(command)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        return new PaymentResult(previous, true);
    }
}
