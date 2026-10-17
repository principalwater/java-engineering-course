package dev.principalwater.bank.cash;

import dev.principalwater.bank.contract.security.OAuthScope;
import dev.principalwater.bank.runtime.security.BearerApiSecurity;
import dev.principalwater.bank.runtime.security.JwtAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class CashSecurity {
    @Bean
    SecurityFilterChain cashSecurityFilterChain(HttpSecurity http) throws Exception {
        return BearerApiSecurity.stateless(http)
                .authorizeHttpRequests(
                        routes ->
                                routes.requestMatchers(HttpMethod.POST, "/api/cash")
                                        .access(JwtAccess.user(OAuthScope.CASH_WRITE))
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
