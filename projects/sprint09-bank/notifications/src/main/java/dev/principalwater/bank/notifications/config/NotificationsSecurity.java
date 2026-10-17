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
    @Bean
    SecurityFilterChain notificationsSecurityFilterChain(HttpSecurity http) throws Exception {
        return BearerApiSecurity.stateless(http)
                .authorizeHttpRequests(
                        routes ->
                                routes.requestMatchers(HttpMethod.POST, "/internal/notifications")
                                        .access(
                                                JwtAccess.service(
                                                        "notifications-api",
                                                        OAuthScope.NOTIFICATIONS_WRITE,
                                                        BankTokenProfile.ACCOUNTS_CLIENT_ID,
                                                        BankTokenProfile.CASH_CLIENT_ID,
                                                        BankTokenProfile.TRANSFER_CLIENT_ID))
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
