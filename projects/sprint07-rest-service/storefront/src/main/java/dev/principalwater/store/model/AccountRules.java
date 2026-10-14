package dev.principalwater.store.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AccountRules {
    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 32;
    public static final int DISPLAY_NAME_MAX_LENGTH = 64;
    public static final int PASSWORD_MIN_LENGTH = 12;
    public static final int PASSWORD_MAX_BYTES = 72;
    public static final String USERNAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._\\-]{2,31}";

    private AccountRules() {
    }

    public static String normalizeUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public static boolean validCredentials(String username, String password) {
        return username != null && username.length() <= USERNAME_MAX_LENGTH
                && username.matches(USERNAME_PATTERN) && password != null
                && !password.isBlank() && password.length() >= PASSWORD_MIN_LENGTH && password.length() <= PASSWORD_MAX_BYTES
                && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }
}
