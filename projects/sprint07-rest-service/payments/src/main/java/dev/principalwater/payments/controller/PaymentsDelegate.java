package dev.principalwater.payments.controller;

import dev.principalwater.payments.generated.api.PaymentsApiDelegate;
import dev.principalwater.payments.generated.model.BalanceResponse;
import dev.principalwater.payments.generated.model.DebitRequest;
import dev.principalwater.payments.generated.model.PaymentReceipt;
import dev.principalwater.payments.model.*;

import dev.principalwater.payments.service.PaymentService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class PaymentsDelegate implements PaymentsApiDelegate {
    private final PaymentService payments;

    public PaymentsDelegate(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    public Mono<ResponseEntity<PaymentReceipt>> debit(UUID key, Mono<DebitRequest> request, ServerWebExchange exchange) {
        return request.switchIfEmpty(Mono.error(new PaymentException(PaymentError.INVALID_REQUEST)))
                .flatMap(input -> {
                    if (input.getAmountMinor() == null || input.getCurrency() == null) {
                        return Mono.error(new PaymentException(PaymentError.INVALID_REQUEST));
                    }
                    return payments.debit(key, new DebitCommand(input.getAccountId(), input.getReferenceId(),
                            input.getAmountMinor(), input.getCurrency().getValue()));
                }).map(result -> {
                    PaymentDecision decision = result.decision();
                    DebitCommand command = decision.command();
                    var receipt = new PaymentReceipt(decision.paymentId(), command.accountId(), command.referenceId(),
                            command.amountMinor(), decision.balanceMinor(), PaymentReceipt.CurrencyEnum.RUB,
                            PaymentReceipt.StatusEnum.fromValue(decision.status().getValue()));
                    HttpStatus status = decision.status() == PaymentStatus.DECLINED ? HttpStatus.PAYMENT_REQUIRED
                            : result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
                    return ResponseEntity.status(status).body(receipt);
                });
    }

    @Override
    public Mono<ResponseEntity<BalanceResponse>> getBalance(UUID accountId, ServerWebExchange exchange) {
        return payments.balance(accountId).map(balance -> ResponseEntity.ok(
                new BalanceResponse(accountId, balance, BalanceResponse.CurrencyEnum.RUB)));
    }
}
