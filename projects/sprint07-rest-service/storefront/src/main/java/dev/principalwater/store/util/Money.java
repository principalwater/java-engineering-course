package dev.principalwater.store.util;

import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    public static final long MAX_MINOR = 1_000_000_000_000_000L;
    public static final String CURRENCY = "RUB";
    private static final long MINOR_PER_RUBLE = 100;
    private Money() { }
    public static long minor(long rubles) { return Math.multiplyExact(rubles, MINOR_PER_RUBLE); }
    public static long rublesExact(long minor) {
        if (minor % MINOR_PER_RUBLE != 0) { throw new IllegalArgumentException("Catalog total must contain whole rubles"); }
        return minor / MINOR_PER_RUBLE;
    }
    public static String label(long minor) {
        // BigDecimal нужен только на границе отображения; в расчётах и БД используются целые копейки.
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"))
                .format(java.math.BigDecimal.valueOf(minor, 2));
    }
}
