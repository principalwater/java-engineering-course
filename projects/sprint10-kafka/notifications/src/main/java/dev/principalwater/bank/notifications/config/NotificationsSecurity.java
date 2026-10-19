package dev.principalwater.bank.notifications.config;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import dev.principalwater.bank.contract.security.OAuthScope;
import dev.principalwater.bank.runtime.security.BearerApiSecurity;
import dev.principalwater.bank.runtime.security.JwtAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class NotificationsSecurity {
    private static final String AUDIENCE = "notifications-api";

    @Bean
    SecurityFilterChain notificationsSecurityFilterChain(HttpSecurity http) throws Exception {
        return BearerApiSecurity.stateless(http)
                .authorizeHttpRequests(
                        routes ->
                                routes.requestMatchers(HttpMethod.GET, "/internal/notifications/*")
                                        // Accounts определяет владельца по JWT; браузер и
                                        // отправители денег не читают receipts.
                                        .access(
                                                JwtAccess.service(
                                                        AUDIENCE,
                                                        OAuthScope.NOTIFICATIONS_WRITE,
                                                        BankTokenProfile.ACCOUNTS_CLIENT_ID))
                                        .requestMatchers(HttpMethod.POST, "/internal/notifications")
                                        .access(
                                                JwtAccess.service(
                                                        AUDIENCE,
                                                        OAuthScope.NOTIFICATIONS_WRITE,
                                                        BankTokenProfile.ACCOUNTS_CLIENT_ID,
                                                        BankTokenProfile.CASH_CLIENT_ID,
                                                        BankTokenProfile.TRANSFER_CLIENT_ID))
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
