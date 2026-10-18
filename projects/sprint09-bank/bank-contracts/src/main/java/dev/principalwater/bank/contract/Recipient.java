package dev.principalwater.bank.contract;

import java.util.UUID;

public record Recipient(UUID accountId, String login, String fullName) {}
