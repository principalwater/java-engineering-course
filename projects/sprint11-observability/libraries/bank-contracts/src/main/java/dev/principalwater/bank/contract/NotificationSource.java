package dev.principalwater.bank.contract;

import dev.principalwater.bank.contract.security.BankTokenProfile;

public enum NotificationSource {
    ACCOUNTS(BankTokenProfile.ACCOUNTS_CLIENT_ID, BankTopics.ACCOUNTS_NOTIFICATIONS),
    CASH(BankTokenProfile.CASH_CLIENT_ID, BankTopics.CASH_NOTIFICATIONS),
    TRANSFER(BankTokenProfile.TRANSFER_CLIENT_ID, BankTopics.TRANSFER_NOTIFICATIONS);

    private final String caller;
    private final String topic;

    NotificationSource(String caller, String topic) {
        this.caller = caller;
        this.topic = topic;
    }

    public String getCaller() {
        return caller;
    }

    public String getTopic() {
        return topic;
    }

    public String identity(java.util.UUID eventId) {
        return name() + ":" + eventId;
    }

    public static NotificationSource fromTopic(String topic) {
        for (NotificationSource source : values()) {
            if (source.topic.equals(topic)) return source;
        }
        throw new IllegalArgumentException("Unknown notification topic");
    }
}
