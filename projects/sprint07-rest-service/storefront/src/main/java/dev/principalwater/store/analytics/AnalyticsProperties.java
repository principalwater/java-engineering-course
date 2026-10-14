package dev.principalwater.store.analytics;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("store.analytics")
public class AnalyticsProperties {
    private URI url;
    private List<URI> urls = List.of();
    private boolean distributed;
    @Min(0) @Max(10) private int insertQuorum;
    @NotBlank private String writerPassword;
    @NotBlank private String readerPassword;

    // File secrets читаются через Config tree; рабочее окружение использует внешний secret manager и TLS.
    // Обычный класс не получает автоматически созданный toString с паролями, как record.
    public List<URI> getUrls() { return urls; }
    public void setUrls(List<URI> urls) { this.urls = List.copyOf(urls); }
    public List<URI> endpoints() { return urls.isEmpty() ? java.util.Collections.singletonList(url) : urls; }
    public boolean isDistributed() { return distributed; }
    public void setDistributed(boolean distributed) { this.distributed = distributed; }
    public int getInsertQuorum() { return insertQuorum; }
    public void setInsertQuorum(int quorum) { insertQuorum = quorum; }
    public URI getUrl() { return url; }
    public void setUrl(URI url) { this.url = url; }
    public String getWriterPassword() { return writerPassword; }
    public void setWriterPassword(String password) { writerPassword = password; }
    public String getReaderPassword() { return readerPassword; }
    public void setReaderPassword(String password) { readerPassword = password; }
}
