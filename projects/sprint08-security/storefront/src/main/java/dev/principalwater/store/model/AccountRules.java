package dev.principalwater.store.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AccountRules {
    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 32;
    public static final int DISPLAY_NAME_MAX_LENGTH = 64;
    public static final int PASSWORD_MIN_LENGTH = 12;
    public static final int PASSWORD_MAX_BYTES = 72;
    public static final int EMAIL_MAX_LENGTH = 254;
    public static final String USERNAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._\\-]{2,31}";
    // Сегменты разделены одиночными точками, поэтому разбор линейный; ASCII достаточно для адресов демонстрационного магазина.
    public static final String EMAIL_PATTERN = "[A-Za-z0-9_%+\\-]{1,64}(?:\\.[A-Za-z0-9_%+\\-]{1,64}){0,15}"
            + "@[A-Za-z0-9\\-]{1,63}(?:\\.[A-Za-z0-9\\-]{1,63}){1,8}";
    private static final int EMAIL_LOCAL_MAX_LENGTH = 64;
    // 32 случайных байта в base64url без заполнения занимают ровно 43 символа.
    public static final int RECOVERY_TOKEN_BYTES = 32;
    public static final String RECOVERY_TOKEN_PATTERN = "[A-Za-z0-9_\\-]{43}";

    private AccountRules() {
    }

    public static String normalizeUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    // Уникальность и поиск идут по нормализованной форме: регистр адреса не создаёт второй аккаунт.
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean validEmail(String email) {
        return email != null && email.length() <= EMAIL_MAX_LENGTH && email.matches(EMAIL_PATTERN)
                && email.indexOf('@') <= EMAIL_LOCAL_MAX_LENGTH;
    }

    public static boolean validCredentials(String username, String password) {
        return username != null && username.length() <= USERNAME_MAX_LENGTH
                && username.matches(USERNAME_PATTERN) && validPassword(password);
    }

    // BCrypt принимает не более 72 байт: лимит считается в UTF-8, а не в символах.
    public static boolean validPassword(String password) {
        return password != null && !password.isBlank() && password.length() >= PASSWORD_MIN_LENGTH
                && password.length() <= PASSWORD_MAX_BYTES
                && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }
}
