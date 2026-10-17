package dev.principalwater.payments.dao;

import dev.principalwater.payments.model.DebitCommand;
import dev.principalwater.payments.model.PaymentDecision;
import dev.principalwater.payments.model.PaymentError;
import dev.principalwater.payments.model.PaymentException;
import dev.principalwater.payments.model.PaymentStatus;

import io.r2dbc.spi.Row;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class PaymentRepository {
    private static final String DECISIONS = "SELECT payment_id, account_id, reference_id, amount_minor, currency, status, balance_minor FROM payment_decisions ";
    private static final String ACCOUNT_PARAMETER = "account";
    private final DatabaseClient database;

    public PaymentRepository(DatabaseClient database) {
        this.database = database;
    }

    public Mono<Long> balance(UUID accountId) {
        return database.sql("SELECT balance_minor FROM payment_wallets WHERE account_id = :account")
                .bind(ACCOUNT_PARAMETER, accountId).map(row -> row.get("balance_minor", Long.class)).one();
    }

    public Mono<PaymentDecision> decision(UUID key) {
        return database.sql(DECISIONS + "WHERE payment_id = :key").bind("key", key).map((row, metadata) -> decision(row)).one();
    }

    public Mono<PaymentDecision> reference(UUID account, UUID reference) {
        return database.sql(DECISIONS + "WHERE account_id = :account AND reference_id = :reference")
                .bind(ACCOUNT_PARAMETER, account).bind("reference", reference).map((row, metadata) -> decision(row)).one();
    }

    public Mono<Void> createWallet(UUID account, long opening) {
        // 0 строк — кошелёк уже открыт вместе со своей проводкой открытия.
        return insertWalletIfAbsent(account, opening)
                .flatMap(created -> created == 0 ? Mono.empty() : appendOpeningJournal(account, opening));
    }

    public Mono<Long> lockWallet(UUID account) {
        return database.sql("SELECT balance_minor FROM payment_wallets WHERE account_id = :account FOR UPDATE")
                .bind(ACCOUNT_PARAMETER, account).map(row -> row.get("balance_minor", Long.class)).one();
    }

    public Mono<Void> save(PaymentDecision decision) {
        DebitCommand command = decision.command();
        return database.sql("""
                INSERT INTO payment_decisions(payment_id, account_id, reference_id, amount_minor, currency, status, balance_minor)
                VALUES (:key, :account, :reference, :amount, :currency, :status, :balance)
                """).bind("key", decision.paymentId()).bind(ACCOUNT_PARAMETER, command.accountId())
                .bind("reference", command.referenceId()).bind("amount", command.amountMinor()).bind("currency", command.currency())
                .bind("status", decision.status().getValue()).bind("balance", decision.balanceMinor())
                .fetch().rowsUpdated().flatMap(this::requireOne);
    }

    // Шаги идут в транзакции PaymentService после save(): проводка ссылается на решение внешним ключом.
    public Mono<Void> debit(PaymentDecision decision, long previousBalance) {
        return withdrawFromWallet(decision, previousBalance).then(appendDebitJournal(decision));
    }

    // Гонку создателей одного кошелька решает PK: проигравший получает DuplicateKeyException, транзакцию повторяет PaymentService.
    private Mono<Long> insertWalletIfAbsent(UUID account, long opening) {
        return database.sql("""
                INSERT INTO payment_wallets(account_id, opening_balance_minor, balance_minor, currency)
                SELECT :account, :opening, :opening, 'RUB'
                WHERE NOT EXISTS (SELECT 1 FROM payment_wallets WHERE account_id = :account)
                """).bind(ACCOUNT_PARAMETER, account).bind("opening", opening).fetch().rowsUpdated();
    }

    private Mono<Void> appendOpeningJournal(UUID account, long opening) {
        return database.sql("""
                INSERT INTO payment_journal(kind, journal_id, account_id, opening_minor, positive_account, negative_account,
                                            positive_minor, negative_minor)
                VALUES ('OPENING', :account, :account, :opening, 'CUSTOMER', 'ISSUER', :opening, :opposite)
                """).bind(ACCOUNT_PARAMETER, account).bind("opening", opening).bind("opposite", Math.negateExact(opening))
                .fetch().rowsUpdated().flatMap(this::requireOne);
    }

    // Условный UPDATE: ровно одна строка, пока остаток равен прочитанному под FOR UPDATE и покрывает сумму.
    private Mono<Void> withdrawFromWallet(PaymentDecision decision, long previousBalance) {
        DebitCommand command = decision.command();
        return database.sql("""
                UPDATE payment_wallets SET balance_minor = :balance
                WHERE account_id = :account AND balance_minor = :previous AND balance_minor >= :amount
                """).bind("balance", decision.balanceMinor()).bind(ACCOUNT_PARAMETER, command.accountId())
                .bind("previous", previousBalance).bind("amount", command.amountMinor())
                .fetch().rowsUpdated().flatMap(this::requireOne);
    }

    private Mono<Void> appendDebitJournal(PaymentDecision decision) {
        DebitCommand command = decision.command();
        return database.sql("""
                INSERT INTO payment_journal(kind, journal_id, payment_id, account_id, payment_status, positive_account, negative_account,
                                            positive_minor, negative_minor)
                VALUES ('DEBIT', :key, :key, :account, 'SUCCEEDED', 'CLEARING', 'CUSTOMER', :amount, :opposite)
                """).bind("key", decision.paymentId()).bind(ACCOUNT_PARAMETER, command.accountId())
                .bind("amount", command.amountMinor()).bind("opposite", Math.negateExact(command.amountMinor()))
                .fetch().rowsUpdated().flatMap(this::requireOne);
    }

    private PaymentDecision decision(Row row) {
        var command = new DebitCommand(row.get("account_id", UUID.class), row.get("reference_id", UUID.class),
                row.get("amount_minor", Long.class), row.get("currency", String.class));
        return new PaymentDecision(row.get("payment_id", UUID.class), command, row.get("balance_minor", Long.class),
                PaymentStatus.valueOf(row.get("status", String.class)));
    }

    private Mono<Void> requireOne(long count) {
        return count == 1 ? Mono.empty() : Mono.error(new PaymentException(PaymentError.UNAVAILABLE));
    }
}
