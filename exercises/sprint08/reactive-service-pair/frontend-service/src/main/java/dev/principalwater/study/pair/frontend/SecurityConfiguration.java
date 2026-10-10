package dev.principalwater.study.pair.frontend;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    MapReactiveUserDetailsService users(PasswordEncoder encoder, @Value("${COURSE_USER_PASSWORD}") String password) {
        return new MapReactiveUserDetailsService(User.withUsername("user")
                .password(encoder.encode(password)).roles("USER").build());
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        var logoutSuccess = new RedirectServerLogoutSuccessHandler();
        logoutSuccess.setLogoutSuccessUrl(URI.create("/"));
        return http.authorizeExchange(auth -> auth
                        .pathMatchers("/", "/login", "/style.css").permitAll()
                        .anyExchange().authenticated())
                .formLogin(form -> form.loginPage("/login")
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/message")))
                .logout(logout -> logout.logoutUrl("/logout")
                        .logoutHandler(new DelegatingServerLogoutHandler(
                                new SecurityContextServerLogoutHandler(), new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler(logoutSuccess))
                .build();
    }
}
