package dev.principalwater.bank.runtime.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

public final class OAuthTokenClients {
    private OAuthTokenClients() {}

    // Builder внедряется Boot и несёт наблюдение клиента; собственная фабрика запросов сохраняет
    // тайм-ауты и пул соединений.
    public static RestClient create(
            RestClient.Builder builder, JdkClientHttpRequestFactory requests) {
        return builder.requestFactory(requests)
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
