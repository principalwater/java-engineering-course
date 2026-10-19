package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.NotificationInbox;
import dev.principalwater.bank.contract.NotificationView;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AccountNotifications {
    private static final ParameterizedTypeReference<Page<NotificationView>> PAGE =
            new ParameterizedTypeReference<>() {};
    private final ProfileService profiles;
    private final ServiceClient notifications;

    public AccountNotifications(ProfileService profiles, ServiceClient notifications) {
        this.profiles = profiles;
        this.notifications = notifications;
    }

    public NotificationInbox inbox(Actor actor, Long before, int limit) {
        // Короткая транзакция Accounts завершена до HTTP: соединение БД не ждёт Notifications.
        var owner = profiles.own(actor).accountId();
        String path =
                UriComponentsBuilder.fromPath("/internal/notifications/{owner}")
                        .queryParam("limit", limit)
                        .queryParamIfPresent("before", Optional.ofNullable(before))
                        .buildAndExpand(owner)
                        .toUriString();
        try {
            return new NotificationInbox(owner, notifications.get("notifications", path, PAGE));
        } catch (RemoteUnavailable exception) {
            // Недоступность доставки не подменяется успешным ответом с пустой лентой.
            throw new BankException(BankError.UNAVAILABLE);
        }
    }
}
