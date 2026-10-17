package dev.principalwater.store.controller;

// Единственный владелец адресов, которые называют и контроллеры, и политика безопасности.
public final class StorePaths {
    public static final String LOGIN = "/account/login";
    public static final String LOGOUT = "/account/logout";
    public static final String REGISTER = "/account/register";
    public static final String RECOVERY = "/account/recovery";
    public static final String RECOVERY_RESET = RECOVERY + "/reset";
    public static final String CABINET = "/cabinet";
    public static final String CABINET_ANALYTICS = CABINET + "/analytics";
    public static final String STORE = CABINET + "/store";
    public static final String STORE_ANALYTICS = STORE + "/analytics";
    public static final String ITEMS = "/items";
    public static final String CATALOG = "/catalog";

    private StorePaths() {
    }
}
