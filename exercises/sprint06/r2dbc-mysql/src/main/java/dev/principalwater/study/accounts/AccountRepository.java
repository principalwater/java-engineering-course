package dev.principalwater.study.accounts;

import java.math.BigDecimal;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AccountRepository extends ReactiveCrudRepository<Account, Integer> {
    Flux<Account> findAccountsByBalanceGreaterThan(BigDecimal amount);
}
