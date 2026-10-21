package dev.principalwater.bank.contract.security;

public final class BankTokenProfile {
    public static final String USER_AUDIENCE = "bank-api";
    public static final String FRONT_CLIENT_ID = "bank-front";
    public static final String ACCOUNTS_CLIENT_ID = "accounts-service";
    public static final String CASH_CLIENT_ID = "cash-service";
    public static final String TRANSFER_CLIENT_ID = "transfer-service";
    public static final String ANALYTICS_CLIENT_ID = "analytics-service";
    public static final String ANALYST_ROLE = "analyst";
    public static final String REALM_ACCESS_CLAIM = "realm_access";
    public static final String REALM_ROLES_CLAIM = "roles";

    private BankTokenProfile() {}
}
