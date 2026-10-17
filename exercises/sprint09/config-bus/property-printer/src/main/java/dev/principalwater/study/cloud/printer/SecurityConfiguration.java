package dev.principalwater.study.cloud.printer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(request ->
                        "bus".equals(request.getHeader("X-Lab-Operation"))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/texts").permitAll()
                        .requestMatchers("/actuator/busrefresh", "/actuator/busshutdown").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
