package dev.principalwater.study.accounts;

import java.math.BigDecimal;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AccountService {
    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Flux<Account> saveAll(Account... values) {
        return accounts.saveAll(Arrays.asList(values));
    }

    public Flux<Account> findRichAccounts(BigDecimal minimum) {
        return accounts.findAccountsByBalanceGreaterThan(minimum);
    }
}
