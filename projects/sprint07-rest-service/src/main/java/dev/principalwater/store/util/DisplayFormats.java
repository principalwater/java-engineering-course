package dev.principalwater.store.util;

import dev.principalwater.store.config.DisplayProperties;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component("display")
public class DisplayFormats {
    private static final Locale LOCALE = Locale.forLanguageTag("ru-RU");
    private static final String CURRENCY = " ₽";
    private final DateTimeFormatter time;

    public DisplayFormats(DisplayProperties properties) {
        time = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", LOCALE).withZone(properties.zone());
    }

    public String money(long rubles) {
        return NumberFormat.getIntegerInstance(LOCALE).format(rubles) + CURRENCY;
    }

    public String money(BigDecimal rubles) {
        var format = NumberFormat.getNumberInstance(LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(rubles) + CURRENCY;
    }

    public String time(Instant value) {
        return time.format(value);
    }

    public String zone() {
        return time.getZone().getId();
    }
}
