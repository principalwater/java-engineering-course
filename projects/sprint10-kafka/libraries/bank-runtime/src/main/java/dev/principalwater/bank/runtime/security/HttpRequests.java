package dev.principalwater.bank.runtime.security;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.client.JdkClientHttpRequestFactory;

public final class HttpRequests {
    public static final String DEFAULT_CONNECT_TIMEOUT = "1s";
    public static final String DEFAULT_READ_TIMEOUT = "3s";

    private HttpRequests() {}

    public static JdkClientHttpRequestFactory create() {
        return create(
                DurationStyle.detectAndParse(DEFAULT_CONNECT_TIMEOUT),
                DurationStyle.detectAndParse(DEFAULT_READ_TIMEOUT));
    }

    public static JdkClientHttpRequestFactory create(
            Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(connectTimeout)
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        requests.setReadTimeout(readTimeout);
        return requests;
    }
}
