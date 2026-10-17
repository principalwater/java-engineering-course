package dev.principalwater.study.bank.transfer;

import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class AccountsClient {
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private final WebClient client;
    record AccountOwner(String accountId, String ownerUsername) { }

    public AccountsClient(WebClient client) {
        this.client = client;
    }

    public boolean isOwner(String accountId, String username) {
        AccountOwner owner = client.get().uri("/accounts/{accountId}/owner", accountId).retrieve()
                .bodyToMono(AccountOwner.class).block(DEADLINE);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty account owner response");
        }
        return owner.accountId().equals(accountId) && owner.ownerUsername().equals(username);
    }

    String submit(TransferRequest request) {
        String acknowledgement = client.post().uri("/accounts/transfer").bodyValue(request).retrieve()
                .bodyToMono(String.class).block(DEADLINE);
        if (acknowledgement == null || acknowledgement.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty transfer acknowledgement");
        }
        return acknowledgement;
    }
}
