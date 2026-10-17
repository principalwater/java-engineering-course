package dev.principalwater.study.bank.identity;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmAuthoritiesTest {
    static Stream<Arguments> claims() {
        return Stream.of(
                Arguments.of("invalid", List.of()),
                Arguments.of(Map.of("roles", "USER"), List.of()),
                Arguments.of(Map.of("roles", List.of(123, "unknown", "USER", "TRANSFER_WRITE")),
                        List.of("ROLE_USER", "transfer.write")));
    }

    @ParameterizedTest
    @MethodSource("claims")
    void malformedClaimsCannotExpandAuthorities(Object claim, List<String> expected) {
        var jwt = Jwt.withTokenValue("claim-fixture").header("alg", "RS256").claim("realm_access", claim).build();
        assertEquals(expected, new RealmAuthorities().convert(jwt).stream().map(GrantedAuthority::getAuthority).toList());
    }
}
