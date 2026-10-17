package dev.principalwater.study.bank.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class RealmAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Map<String, String> AUTHORITIES = Map.of(
            "USER", "ROLE_USER", "SERVICE", "ROLE_SERVICE",
            "TRANSFER_WRITE", "transfer.write", "ACCOUNTS_WRITE", "accounts.write");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realm = jwt.getClaims().get("realm_access");
        if (!(realm instanceof Map<?, ?> claims) || !(claims.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(AUTHORITIES::containsKey).map(AUTHORITIES::get)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }
}
