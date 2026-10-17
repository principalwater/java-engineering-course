package dev.principalwater.bank.runtime.security;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

public final class OAuthHttpRequests {
    public static final String DEFAULT_CONNECT_TIMEOUT = "1s";
    public static final String DEFAULT_READ_TIMEOUT = "3s";

    private OAuthHttpRequests() {}

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

    public static RestClient tokenClient(JdkClientHttpRequestFactory requests) {
        return RestClient.builder()
                .requestFactory(requests)
                .messageConverters(
                        converters -> {
                            converters.clear();
                            converters.add(new FormHttpMessageConverter());
                            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                        })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
