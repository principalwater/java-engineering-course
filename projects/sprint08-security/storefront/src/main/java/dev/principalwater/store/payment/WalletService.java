package dev.principalwater.store.payment;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class WalletService {
    private static final Logger LOG = LoggerFactory.getLogger(WalletService.class);
    private final PaymentsGateway payments;
    public WalletService(PaymentsGateway payments) { this.payments = payments; }
    public Mono<WalletBalanceView> balance(UUID payerId) {
        return payments.balance(payerId).map(WalletBalanceView::available)
                // Не печатаем платёжный ответ, данные аккаунта и текст исключения с возможными секретами.
                .doOnError(failure -> LOG.warn("Payment balance is unavailable: {}", failure.getClass().getSimpleName()))
                .onErrorReturn(WalletBalanceView.unavailable());
    }
}
