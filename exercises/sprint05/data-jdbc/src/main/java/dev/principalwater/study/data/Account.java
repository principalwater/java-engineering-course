package dev.principalwater.study.data;

import java.math.BigDecimal;

public record Account(Long id, String name, BigDecimal balance) {}
