package dev.principalwater.bank.observability.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.logstash.logback.composite.loggingevent.ThrowableRootCauseClassNameJsonProvider;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashCommonFieldNames;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;

// Единый безопасный JSON-формат событий для всех сервисов. Выводятся только поля:
// @timestamp, service, level, logger_name, event (текст сообщения), traceId, spanId и category
// (класс первопричины ошибки). Аргументы, MDC вне белого списка, stack trace и текст исключений
// не покидают процесс: в них встречаются SQL с параметрами, заголовки и данные счетов.
public final class BankLogEncoder extends LogstashEncoder {
    private static final String IGNORED = LogstashCommonFieldNames.IGNORE_FIELD_INDICATOR;
    private static final String REDACTED = "[redacted]";
    // Длина значения ограничена, чтобы одно событие не заняло очередь и не превысило лимит
    // строки Logstash; ограничение применяется последним, после замены секретов.
    private static final int MAX_VALUE_LENGTH = 1024;
    private static final String TRUNCATE_PATTERN = "(?s)^(.{" + MAX_VALUE_LENGTH + "}).+$";
    private static final String TRUNCATED = "$1[truncated]";
    // JWT: три сегмента base64url, первый начинается с eyJ ({"...).
    private static final String JWT_PATTERN =
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*";
    // Схемы Authorization с учётными данными.
    private static final String AUTH_SCHEME_PATTERN =
            "(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}";
    // Заголовки и поля с секретами в виде name: value или name=value, включая составные имена
    // (x-user-token, refresh_token, client_secret).
    private static final String SECRET_FIELD_PATTERN =
            "(?i)[\\w-]*(?:authorization|cookie|x-owner-id|x-account-id|x-actor|password|passwd"
                    + "|secret|token|private[_-]?key|api[_-]?key)[\\w-]*\\s*[:=]\\s*[^\\s,;&]+";
    private static final List<String> SECRET_PATTERNS =
            List.of(
                    JWT_PATTERN,
                    AUTH_SCHEME_PATTERN,
                    SECRET_FIELD_PATTERN,
                    "(?s)-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z]+ )?PRIVATE KEY-----");
    private static final List<String> MDC_FIELDS = List.of("traceId", "spanId");
    private final MaskingJsonGeneratorDecorator masks = new MaskingJsonGeneratorDecorator();

    public BankLogEncoder() {
        setTimeZone("UTC");
        setIncludeContext(false);
        setIncludeCallerData(false);
        setIncludeTags(false);
        setIncludeKeyValuePairs(false);
        setIncludeStructuredArguments(false);
        setIncludeNonStructuredArguments(false);
        MDC_FIELDS.forEach(this::addIncludeMdcKeyName);

        LogstashFieldNames names = getFieldNames();
        names.setTimestamp("@timestamp");
        names.setMessage("event");
        names.setVersion(IGNORED);
        names.setThread(IGNORED);
        names.setLevelValue(IGNORED);
        names.setStackTrace(IGNORED);
        ThrowableRootCauseClassNameJsonProvider category =
                new ThrowableRootCauseClassNameJsonProvider();
        category.setFieldName("category");
        addProvider(category);

        masks.setDefaultMask(REDACTED);
        SECRET_PATTERNS.forEach(masks::addValue);
        masks.addValueMask(
                new MaskingJsonGeneratorDecorator.ValueMask(TRUNCATE_PATTERN, TRUNCATED));
        setJsonGeneratorDecorator(masks);
    }

    // Имя сервиса задаётся из spring.application.name через logback-spring.xml.
    public void setService(String service) {
        try {
            setCustomFields(new ObjectMapper().writeValueAsString(Map.of("service", service)));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid service name", failure);
        }
    }

    @Override
    public void start() {
        // Декоратор программной конфигурации не запускается Joran, поэтому запуск здесь.
        masks.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        masks.stop();
    }
}
