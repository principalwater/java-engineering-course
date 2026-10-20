package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.accounts.dao.AccountDao;
import dev.principalwater.bank.accounts.model.Account;
import dev.principalwater.bank.accounts.model.ProfileRules;
import dev.principalwater.bank.contract.AccountView;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.HistoryEntry;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.contract.Recipient;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final AccountDao accounts;
    private final KafkaOutbox outbox;
    private final Clock clock;
    private final boolean analyticsEnabled;

    public ProfileService(
            AccountDao accounts,
            KafkaOutbox outbox,
            Clock clock,
            @Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.analyticsEnabled = analyticsEnabled;
        this.accounts = accounts;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public AccountView own(Actor actor) {
        return accounts.provision(actor).view();
    }

    @Transactional
    public Page<Recipient> recipients(Actor actor, String after, String search, int limit) {
        return accounts.recipients(accounts.provision(actor).id(), after, search, limit);
    }

    @Transactional
    public Page<HistoryEntry> history(Actor actor, String before, int limit) {
        return accounts.history(accounts.provision(actor).id(), before, limit);
    }

    @Transactional
    public AccountView update(Actor actor, ProfileRequest request) {
        String name =
                ProfileRules.validate(
                        request.fullName(), request.birthDate(), LocalDate.now(clock));
        Account account = accounts.provision(actor);
        if (!accounts.updateProfile(account.id(), name, request.birthDate(), request.version())) {
            throw new BankException(BankError.STALE_PROFILE);
        }
        UUID eventId = UUID.randomUUID();
        var now = clock.instant();
        BankEvent event =
                new BankEvent(
                        eventId,
                        account.id(),
                        "PROFILE_UPDATED",
                        0,
                        account.balanceMinor(),
                        now,
                        actor.login());
        outbox.enqueue(
                NotificationSource.ACCOUNTS.getTopic(),
                account.id().toString(),
                eventId.toString(),
                event);
        if (analyticsEnabled) {
            outbox.enqueue(
                    BankTopics.ACCOUNTS_ANALYTICS,
                    account.id().toString(),
                    eventId.toString(),
                    new BankEvent(eventId, account.id(), "PROFILE_UPDATED", 0, 0, now));
        }
        return accounts.provision(actor).view();
    }
}
