package dev.principalwater.bank.front.config;

import dev.principalwater.bank.contract.security.OAuthScope;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class FrontSecurity {
    private static final String LOGIN_PATH = "/login";
    private static final String PROTOCOL = "/protocol/openid-connect";
    private static final String CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

    @Bean
    ClientRegistrationRepository bankRegistration(LoginProperties properties) {
        String issuer = properties.issuer().toString();
        // Явные backchannel endpoints сохраняют canonical issuer и его проверку в ID Token.
        ClientRegistration registration =
                ClientRegistration.withRegistrationId(FrontOAuth.REGISTRATION_ID)
                        .clientId(properties.clientId())
                        .clientSecret(properties.clientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .scope(
                                OAuthScope.toStrings(
                                        OAuthScope.OPENID,
                                        OAuthScope.PROFILE,
                                        OAuthScope.ACCOUNTS_READ,
                                        OAuthScope.ACCOUNTS_WRITE,
                                        OAuthScope.CASH_WRITE,
                                        OAuthScope.TRANSFER_WRITE,
                                        OAuthScope.ANALYTICS_READ,
                                        OAuthScope.ANALYTICS_EVENTS))
                        .authorizationUri(issuer + PROTOCOL + "/auth")
                        .tokenUri(properties.tokenUri().toString())
                        .jwkSetUri(properties.jwkSetUri().toString())
                        .userInfoUri(properties.userInfoUri().toString())
                        .userNameAttributeName(StandardClaimNames.PREFERRED_USERNAME)
                        .issuerUri(issuer)
                        .providerConfigurationMetadata(
                                Map.of(
                                        FrontOAuth.END_SESSION_ENDPOINT,
                                        issuer + PROTOCOL + "/logout"))
                        .clientName(FrontOAuth.CLIENT_NAME)
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
        return http.authorizeHttpRequests(this::configureRoutes)
                .exceptionHandling(this::configureEntryPoints)
                .oauth2Login(login -> configureLogin(login, registrations, tokens, users))
                .logout(logout -> configureLogout(logout, registrations))
                .headers(headers -> configureHeaders(headers, registrations))
                .build();
    }

    private void configureLogout(
            LogoutConfigurer<HttpSecurity> logout, ClientRegistrationRepository registrations) {
        logout.logoutSuccessHandler(logoutHandler(registrations))
                .invalidateHttpSession(true)
                .deleteCookies("__Host-bank-session");
    }

    private void configureHeaders(
            HeadersConfigurer<HttpSecurity> headers, ClientRegistrationRepository registrations) {
        String policy = contentSecurityPolicy(registrations);
        headers.contentSecurityPolicy(directives -> directives.policyDirectives(policy));
    }

    private void configureRoutes(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    routes) {
        routes.requestMatchers(
                        LOGIN_PATH,
                        "/login.html",
                        "/_astro/**",
                        "/oauth2/**",
                        "/login/oauth2/**",
                        "/error")
                .permitAll();
        routes.anyRequest().authenticated();
    }

    private void configureEntryPoints(ExceptionHandlingConfigurer<HttpSecurity> errors) {
        errors.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/ui/**"));
        errors.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint(LOGIN_PATH), AnyRequestMatcher.INSTANCE);
    }

    private void configureLogin(
            OAuth2LoginConfigurer<HttpSecurity> login,
            ClientRegistrationRepository registrations,
            RestClientAuthorizationCodeTokenResponseClient tokens,
            OidcUserService users) {
        login.loginPage(LOGIN_PATH).defaultSuccessUrl("/cabinet", true);
        login.authorizationEndpoint(
                endpoint ->
                        endpoint.authorizationRequestResolver(
                                authorizationResolver(registrations)));
        login.tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokens));
        login.userInfoEndpoint(endpoint -> endpoint.oidcUserService(users));
    }

    private DefaultOAuth2AuthorizationRequestResolver authorizationResolver(
            ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations,
                        OAuth2AuthorizationRequestRedirectFilter
                                .DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    private OidcClientInitiatedLogoutSuccessHandler logoutHandler(
            ClientRegistrationRepository registrations) {
        OidcClientInitiatedLogoutSuccessHandler logout =
                new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/login?logout");
        return logout;
    }

    private String contentSecurityPolicy(ClientRegistrationRepository registrations) {
        // CSP проверяет и redirect после POST: разрешён только logout настроенного IdP.
        String issuer =
                registrations
                        .findByRegistrationId(FrontOAuth.REGISTRATION_ID)
                        .getProviderDetails()
                        .getIssuerUri();
        return CSP + "; form-action 'self' " + issuer + PROTOCOL + "/logout";
    }
}
