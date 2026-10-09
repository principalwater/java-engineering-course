package dev.principalwater.study.accounts;

import java.math.BigDecimal;
import org.springframework.data.annotation.Id;

public record Account(@Id Integer id, String name, BigDecimal balance) {
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00");

    public Account(String name) {
        this(null, name, INITIAL_BALANCE);
    }

    public Account(String name, BigDecimal balance) {
        this(null, name, balance);
    }
}
