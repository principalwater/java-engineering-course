package dev.principalwater.store.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.payment.generated.api.PaymentsApi;
import dev.principalwater.store.payment.generated.model.BalanceResponse;
import dev.principalwater.store.payment.generated.model.DebitRequest;
import dev.principalwater.store.payment.generated.model.PaymentReceipt;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
public class PaymentsGateway {
    private final PaymentsApi api;
    private final PaymentProperties properties;
    private final ObjectMapper json;
    private final Scheduler codecScheduler;
    public PaymentsGateway(PaymentsApi api, PaymentProperties properties, ObjectMapper json,
                           @Qualifier("cacheCodecScheduler") Scheduler codecScheduler) {
        this.api = api;
        this.properties = properties;
        this.json = json;
        this.codecScheduler = codecScheduler;
    }
    public Mono<Long> balance(UUID payerId) {
        return api.getBalance(payerId).timeout(properties.balanceTimeout()).map(value -> {
            if (!payerId.equals(value.getAccountId()) || value.getCurrency() != BalanceResponse.CurrencyEnum.RUB
                    || value.getBalanceMinor() == null || value.getBalanceMinor() < 0 || value.getBalanceMinor() > dev.principalwater.store.util.Money.MAX_MINOR) {
                throw new IllegalStateException("Invalid payment balance response");
            }
            return value.getBalanceMinor();
        });
    }
    public Mono<PaymentReceipt> debit(CheckoutIntent intent) {
        var request = new DebitRequest().accountId(intent.payerId()).referenceId(intent.id())
                .amountMinor(intent.amountMinor()).currency(DebitRequest.CurrencyEnum.RUB);
        // Общий deadline включает ожидание codec-очереди и разбор HTTP 402.
        return api.debit(intent.id(), request)
                .onErrorResume(WebClientResponseException.class, failure -> {
                    if (failure.getStatusCode().value() != 402) { return Mono.error(failure); }
                    return Mono.fromCallable(() -> json.readValue(failure.getResponseBodyAsByteArray(), PaymentReceipt.class))
                            .subscribeOn(codecScheduler)
                            .filter(receipt -> receipt.getStatus() == PaymentReceipt.StatusEnum.DECLINED)
                            .switchIfEmpty(Mono.error(new IllegalStateException("Invalid declined payment response")));
                }).map(receipt -> {
                    if (!intent.payerId().equals(receipt.getAccountId()) || !intent.id().equals(receipt.getReferenceId())
                            || receipt.getPaymentId() == null || receipt.getAmountMinor() == null
                            || receipt.getAmountMinor() != intent.amountMinor() || receipt.getCurrency() != PaymentReceipt.CurrencyEnum.RUB
                            || receipt.getBalanceMinor() == null || receipt.getBalanceMinor() < 0
                            || receipt.getBalanceMinor() > dev.principalwater.store.util.Money.MAX_MINOR || receipt.getStatus() == null) {
                        throw new IllegalStateException("Payment receipt does not match the checkout intent");
                    }
                    return receipt;
                }).timeout(properties.debitTimeout());
    }
}
