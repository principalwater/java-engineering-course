package dev.principalwater.store.util;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class ExactNumbers {
    private ExactNumbers() { }

    public static long longValueExact(Number value) {
        // PostgreSQL SUM(bigint) возвращает numeric: обычный longValue скрывает переполнение итога.
        return switch (value) {
            case BigDecimal decimal -> decimal.longValueExact();
            case BigInteger integer -> integer.longValueExact();
            case Long integer -> integer;
            case Integer integer -> integer.longValue();
            case Short integer -> integer.longValue();
            case Byte integer -> integer.longValue();
            default -> throw new IllegalArgumentException("Expected an exact integral number");
        };
    }
}
