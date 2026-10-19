package dev.principalwater.bank.messaging;

import java.util.List;
import java.util.UUID;

public interface RecoverableDelivery {
    List<UUID> pendingIds();

    void recover(UUID id);
}
