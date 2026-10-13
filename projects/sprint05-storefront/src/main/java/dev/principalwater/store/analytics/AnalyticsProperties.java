package dev.principalwater.store.analytics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("store.analytics")
public class AnalyticsProperties {
    @NotNull private URI url;
    @NotBlank private String writerPassword;
    @NotBlank private String readerPassword;

    // File secrets читаются через Config tree; рабочее окружение использует внешний secret manager и TLS.
    // Обычный класс не получает автоматически созданный toString с паролями, как record.
    public URI getUrl() { return url; }
    public void setUrl(URI url) { this.url = url; }
    public String getWriterPassword() { return writerPassword; }
    public void setWriterPassword(String password) { writerPassword = password; }
    public String getReaderPassword() { return readerPassword; }
    public void setReaderPassword(String password) { readerPassword = password; }
}
