package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.accounts.dao.AccountDao;
import dev.principalwater.bank.accounts.model.Account;
import dev.principalwater.bank.accounts.model.ProfileRules;
import dev.principalwater.bank.contract.AccountView;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.HistoryEntry;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.contract.Recipient;
import dev.principalwater.bank.runtime.delivery.RestOutbox;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final AccountDao accounts;
    private final RestOutbox outbox;
    private final Clock clock;

    public ProfileService(AccountDao accounts, RestOutbox outbox, Clock clock) {
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
        outbox.enqueue(
                "notifications",
                "/internal/notifications",
                new BankEvent(
                        UUID.randomUUID(),
                        account.id(),
                        "PROFILE_UPDATED",
                        0,
                        account.balanceMinor(),
                        clock.instant()));
        return accounts.provision(actor).view();
    }
}
