package dev.principalwater.bank.contract;

public final class BankLimits {
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    // Верхняя граница остаётся точной также в JSON Number браузера.
    public static final long MAX_BALANCE_MINOR = 9_000_000_000_000_000L;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_FULL_NAME_LENGTH = 160;
    public static final int MAX_RECIPIENT_SEARCH_LENGTH = 80;
    public static final String DEFAULT_PAGE_SIZE = "20";
    public static final String CURRENCY = "RUB";

    private BankLimits() {}
}
