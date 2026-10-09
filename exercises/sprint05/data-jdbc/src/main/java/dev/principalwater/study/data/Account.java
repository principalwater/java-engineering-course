package dev.principalwater.study.data;

import java.math.BigDecimal;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("account")
public record Account(@Id @Column("id") Long id, @Column("name") String name,
                      @Column("balance") BigDecimal balance) {
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00");

    public Account(String name) { this(null, name, INITIAL_BALANCE); }
}
