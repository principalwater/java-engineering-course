package dev.principalwater.store.payment;

import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class WalletService {
    private final PaymentsGateway payments;
    public WalletService(PaymentsGateway payments) { this.payments = payments; }
    public Mono<WalletBalanceView> balance(UUID payerId) {
        return payments.balance(payerId).map(WalletBalanceView::available)
                .onErrorReturn(WalletBalanceView.unavailable());
    }
}
