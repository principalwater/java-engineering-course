package dev.principalwater.bank.front.config;

import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class FrontSecurity {
    private static final String PROTOCOL = "/protocol/openid-connect";
    private static final String CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

    @Bean
    ClientRegistrationRepository bankRegistration(LoginProperties properties) {
        String issuer = properties.issuer().toString();
        // Явные backchannel endpoints сохраняют canonical issuer и его проверку в ID Token.
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("bank")
                        .clientId(properties.clientId())
                        .clientSecret(properties.clientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .scope(
                                "openid",
                                "profile",
                                "accounts.read",
                                "accounts.write",
                                "cash.write",
                                "transfer.write",
                                "analytics.read",
                                "analytics.events")
                        .authorizationUri(issuer + PROTOCOL + "/auth")
                        .tokenUri(properties.tokenUri().toString())
                        .jwkSetUri(properties.jwkSetUri().toString())
                        .userInfoUri(properties.userInfoUri().toString())
                        .userNameAttributeName("preferred_username")
                        .issuerUri(issuer)
                        .providerConfigurationMetadata(
                                Map.of("end_session_endpoint", issuer + PROTOCOL + "/logout"))
                        .clientName("Банк")
                        .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    SecurityFilterChain frontChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            RestClientAuthorizationCodeTokenResponseClient tokens,
            OidcUserService users)
            throws Exception {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());
        OidcClientInitiatedLogoutSuccessHandler logout =
                new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/login?logout");
        // CSP проверяет и redirect после POST: разрешён только logout настроенного IdP.
        String policy =
                CSP
                        + "; form-action 'self' "
                        + registrations
                                .findByRegistrationId("bank")
                                .getProviderDetails()
                                .getIssuerUri()
                        + PROTOCOL
                        + "/logout";
        return http.authorizeHttpRequests(
                        access ->
                                access.requestMatchers(
                                                "/login",
                                                "/login.html",
                                                "/_astro/**",
                                                "/oauth2/**",
                                                "/login/oauth2/**",
                                                "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.defaultAuthenticationEntryPointFor(
                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                                PathPatternRequestMatcher.withDefaults()
                                                        .matcher("/ui/**"))
                                        .defaultAuthenticationEntryPointFor(
                                                new LoginUrlAuthenticationEntryPoint("/login"),
                                                AnyRequestMatcher.INSTANCE))
                .oauth2Login(
                        login ->
                                login.loginPage("/login")
                                        .defaultSuccessUrl("/cabinet", true)
                                        .authorizationEndpoint(
                                                endpoint ->
                                                        endpoint.authorizationRequestResolver(
                                                                resolver))
                                        .tokenEndpoint(
                                                endpoint ->
                                                        endpoint.accessTokenResponseClient(tokens))
                                        .userInfoEndpoint(
                                                endpoint -> endpoint.oidcUserService(users)))
                .logout(
                        logoutConfig ->
                                logoutConfig
                                        .logoutSuccessHandler(logout)
                                        .invalidateHttpSession(true)
                                        .deleteCookies("__Host-bank-session"))
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                        directives -> directives.policyDirectives(policy)))
                .build();
    }
}
