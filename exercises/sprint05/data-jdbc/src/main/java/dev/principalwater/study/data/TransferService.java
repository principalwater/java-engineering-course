package dev.principalwater.study.data;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {
    private static final int CURRENCY_SCALE = 2;
    private final AccountRepository accounts;
    private final TransactionTemplate transactions;

    public TransferService(AccountRepository accounts, TransactionTemplate transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public void transfer(Long sourceId, Long targetId, BigDecimal amount) {
        if (sourceId == null || targetId == null || sourceId <= 0 || targetId <= 0
                || sourceId.equals(targetId) || amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > CURRENCY_SCALE) {
            throw new IllegalArgumentException("Invalid transfer parameters");
        }
        transactions.executeWithoutResult(transaction -> {
            // shortcut: противоположные переводы могут дать deadlock; перед конкурентным использованием нужны порядок блокировок и retry.
            // Начисление выполняется первым, чтобы проверка ошибки списания требовала настоящего rollback.
            if (accounts.adjustBalance(targetId, amount) != 1
                    || accounts.adjustBalance(sourceId, amount.negate()) != 1) {
                throw new IllegalArgumentException("Account not found");
            }
        });
    }
}
