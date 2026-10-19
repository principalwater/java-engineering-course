package dev.principalwater.bank.messaging;

public final class MessageRejected extends RuntimeException {
    public MessageRejected() {
        super("Message violates the immutable event contract");
    }
}
