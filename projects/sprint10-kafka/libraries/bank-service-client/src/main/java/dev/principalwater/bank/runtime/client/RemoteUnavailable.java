package dev.principalwater.bank.runtime.client;

public final class RemoteUnavailable extends RuntimeException {
    public RemoteUnavailable() {
        super("Remote outcome is unavailable");
    }
}
