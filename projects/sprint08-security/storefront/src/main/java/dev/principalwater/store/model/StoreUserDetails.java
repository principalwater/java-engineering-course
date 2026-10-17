package dev.principalwater.store.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class StoreUserDetails implements UserDetails, CredentialsContainer {
    private final UUID accountId;
    private final UUID cartId;
    private final String username;
    private final String displayName;
    private String passwordHash;
    private final AccountRole role;
    private final long authVersion;

    public StoreUserDetails(StoreUser user) {
        this(user.id(), user.cartId(), user.username(), user.displayName(), user.role(), user.authVersion());
        passwordHash = user.passwordHash();
    }

    // Сессия хранит личность без хеша пароля; этот конструктор восстанавливает principal из Redis.
    public StoreUserDetails(UUID accountId, UUID cartId, String username, String displayName, AccountRole role,
                            long authVersion) {
        this.accountId = accountId;
        this.cartId = cartId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.authVersion = authVersion;
    }

    public boolean isSeller() { return role == AccountRole.SELLER; }
    public AccountRole getRole() { return role; }
    public UUID accountId() { return accountId; }
    public UUID cartId() { return cartId; }
    public String displayName() { return displayName; }
    public String getDisplayName() { return displayName; }
    public long authVersion() { return authVersion; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
    @Override public void eraseCredentials() { passwordHash = null; }
    @Override public String toString() { return "StoreUserDetails[accountId=" + accountId + "]"; }
}
