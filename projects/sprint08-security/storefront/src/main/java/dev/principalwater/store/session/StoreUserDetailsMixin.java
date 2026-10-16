package dev.principalwater.store.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.principalwater.store.model.AccountRole;
import java.util.UUID;

/**
 * Минимальное JSON-представление principal в сессии: только идентичность и эпоха учётных данных.
 * Хеш пароля исключён, права берутся из роли, а явный mixin вводит класс в allowlist Spring Security.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
abstract class StoreUserDetailsMixin {
    @JsonIgnore
    private String passwordHash;

    @JsonCreator
    StoreUserDetailsMixin(@JsonProperty("accountId") UUID accountId, @JsonProperty("cartId") UUID cartId,
                          @JsonProperty("username") String username, @JsonProperty("displayName") String displayName,
                          @JsonProperty("role") AccountRole role, @JsonProperty("authVersion") long authVersion) {
    }
}
