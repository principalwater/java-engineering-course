package dev.principalwater.study.bank.accounts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AccountsContractBase {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;

    @BeforeEach
    protected void configureProtocolFixture() {
        // Только контрактный source set: криптография отдельно проверяется настоящим Keycloak по HTTP.
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("service-account-transfer-service").claim("azp", "transfer-service")
                .claim("realm_access", Map.of("roles", List.of("SERVICE", "ACCOUNTS_WRITE")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        when(decoder.decode("test-token")).thenReturn(jwt);
        RestAssuredMockMvc.mockMvc(mvc);
    }
}
