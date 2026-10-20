package dev.principalwater.bank.front;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.principalwater.bank.contract.ProbePaths;
import dev.principalwater.bank.front.client.GatewayClient;
import dev.principalwater.bank.testing.ServletSecurityTestBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:bank-common.properties",
            "bank.login.client-secret=fixture-secret"
        })
// Наблюдаемость включена явно: Boot отключает экспорт метрик в тестах по умолчанию.
@AutoConfigureObservability
class FrontSecurityTest extends ServletSecurityTestBase {
    @Autowired private ClientRegistrationRepository registrations;
    @Autowired private JwtDecoderFactory<ClientRegistration> decoders;
    @MockitoBean private GatewayClient gateway;

    @DynamicPropertySource
    static void loginProperties(DynamicPropertyRegistry properties) {
        properties.add("bank.login.issuer", JWT::issuer);
        properties.add("bank.login.jwk-set-uri", JWT::jwks);
    }

    @Test
    void authenticatedCabinetAndApiCannotBeReadWithoutLogin() throws Exception {
        http.perform(get("/cabinet"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        http.perform(get("/ui/account")).andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway);
    }

    @Test
    void metricsAreScrapableAnonymouslyOnlyOnTheExactGetPath() throws Exception {
        http.perform(get(ProbePaths.PROMETHEUS))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
        // Остальные пути Actuator и любой другой метод остаются за входом через Keycloak.
        http.perform(post(ProbePaths.PROMETHEUS).with(csrf()))
                .andExpect(status().is3xxRedirection());
        for (String path : List.of(ProbePaths.PROMETHEUS + "/extra", "/actuator/env")) {
            http.perform(get(path)).andExpect(status().is3xxRedirection());
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void browserLogoutPolicyPermitsOnlyTheConfiguredIdentityProvider() throws Exception {
        String policy =
                http.perform(get("/login"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Content-Security-Policy");
        assertTrue(
                policy.contains(
                        "form-action 'self' " + JWT.issuer() + "/protocol/openid-connect/logout"));
        assertTrue(!policy.contains("form-action *"));
    }

    @Test
    void missingAuthorizedClientReturnsApiStatusInsteadOfAnIdpRedirect() throws Exception {
        when(gateway.exchange(eq(HttpMethod.GET), eq("/api/accounts/me"), eq(null), any()))
                .thenThrow(new ClientAuthorizationRequiredException("bank"));
        http.perform(
                        get("/ui/account")
                                .with(
                                        oidcLogin()
                                                .clientRegistration(
                                                        registrations.findByRegistrationId(
                                                                "bank"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void authorizationCodeRequestUsesPkceAndRetainsCanonicalIssuer() throws Exception {
        String location =
                http.perform(get("/oauth2/authorization/bank"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");
        assertTrue(location.contains("response_type=code"));
        assertTrue(location.contains("code_challenge_method=S256"));
        assertTrue(location.contains("code_challenge="));
        assertEquals(
                JWT.issuer(),
                registrations.findByRegistrationId("bank").getProviderDetails().getIssuerUri());
    }

    @Test
    void actualIdTokenDecoderRejectsWrongIssuerAndAudienceOverHttpJwks() {
        ClientRegistration bank = registrations.findByRegistrationId("bank");
        var decoder = decoders.createDecoder(bank);
        String valid =
                JWT.token(
                        "alice",
                        "bank-front",
                        List.of("bank-front"),
                        "openid",
                        JWT.issuer(),
                        Instant.now().plusSeconds(300),
                        "alice");
        assertEquals("alice", decoder.decode(valid).getSubject());
        String wrongIssuer =
                JWT.token(
                        "alice",
                        "bank-front",
                        List.of("bank-front"),
                        "openid",
                        "https://wrong-issuer.example",
                        Instant.now().plusSeconds(300),
                        "alice");
        String wrongAudience =
                JWT.token(
                        "alice",
                        "bank-front",
                        List.of("other-client"),
                        "openid",
                        JWT.issuer(),
                        Instant.now().plusSeconds(300),
                        "alice");
        assertThrows(JwtException.class, () -> decoder.decode(wrongIssuer));
        assertThrows(JwtException.class, () -> decoder.decode(wrongAudience));
    }

    @Test
    void cookieMutationsRequireCsrfAndAuthenticatedLogoutIsPostOnly() throws Exception {
        ClientRegistration bank = registrations.findByRegistrationId("bank");
        String profile =
                "{\"fullName\":\"Alice Example\",\"birthDate\":\"1990-06-15\",\"version\":0}";
        http.perform(
                        put("/ui/account")
                                .with(oidcLogin().clientRegistration(bank))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profile))
                .andExpect(status().isForbidden());
        http.perform(post("/logout").with(oidcLogin().clientRegistration(bank)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }

    @Test
    void bffForwardsUserAccessTokenOnlyThroughGateway() throws Exception {
        ClientRegistration bank = registrations.findByRegistrationId("bank");
        when(gateway.exchange(eq(HttpMethod.GET), eq("/api/accounts/me"), eq(null), any()))
                .thenReturn(
                        ResponseEntity.ok(
                                "{\"login\":\"alice\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        http.perform(get("/ui/account").with(oidcLogin().clientRegistration(bank)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("alice"));
        org.mockito.Mockito.verify(gateway)
                .exchange(HttpMethod.GET, "/api/accounts/me", null, "access-token");
    }

    @Test
    void csrfProtectedMutationReachesBffWithoutAlteringInput() throws Exception {
        ClientRegistration bank = registrations.findByRegistrationId("bank");
        when(gateway.exchange(eq(HttpMethod.PUT), eq("/api/accounts/me"), any(), any()))
                .thenReturn(
                        ResponseEntity.ok(
                                "{\"version\":1}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        http.perform(
                        put("/ui/account")
                                .with(oidcLogin().clientRegistration(bank))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"fullName\":\"Alice"
                                                + " Example\",\"birthDate\":\"1990-06-15\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }
}
