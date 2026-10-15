package dev.principalwater.store.service;

import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.ProfileQueries;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.payment.OrderPaymentView;
import dev.principalwater.store.util.Money;
import dev.principalwater.store.model.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final ProfileQueries profiles;
    private final CheckoutIntentRepository intents;

    public OrderService(OrderRepository orders, OrderLineRepository lines, ProfileQueries profiles, CheckoutIntentRepository intents) {
        this.orders = orders;
        this.lines = lines;
        this.profiles = profiles;
        this.intents = intents;
    }

    public Mono<OrderHistoryPage> history(UUID cartId, int pageSize, String cursor) {
        if (!StoreLimits.PAGE_SIZES.contains(pageSize)) { throw new StoreException(StoreError.INVALID_PAGING); }
        var before = OrderCursor.parse(cursor);
        var window = before == null ? orders.history(cartId, pageSize + 1)
                : orders.historyBefore(cartId, before.createdAt(), before.id(), pageSize + 1);
        // Лишняя строка определяет next без COUNT/OFFSET; owner берётся из principal даже с подменённым cursor.
        return window.collectList().flatMap(selected -> {
            boolean hasNext = selected.size() > pageSize;
            var page = List.copyOf(selected.subList(0, Math.min(pageSize, selected.size())));
            String next = hasNext ? new OrderCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
            if (page.isEmpty()) { return Mono.just(new OrderHistoryPage(List.of(), null)); }
            var ids = page.stream().map(PurchaseOrder::id).toList();
            return lines.findByOrderIdInOrderByOrderIdAscLineNumberAsc(ids).collectList().map(items -> {
                var grouped = items.stream().collect(Collectors.groupingBy(OrderLine::orderId));
                return new OrderHistoryPage(page.stream().map(order -> OrderView.from(order,
                        grouped.getOrDefault(order.id(), List.of()))).toList(), next);
            });
        });
    }

    public Mono<OrderView> get(long id, UUID cartId) {
        return orders.findByIdAndCartId(id, cartId)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.ORDER_NOT_FOUND)))
                .flatMap(order -> lines.findByOrderIdOrderByLineNumber(id).collectList()
                        .map(items -> OrderView.from(order, items)));
    }

    public Mono<OrderPaymentView> payment(long id, UUID cartId) {
        return orders.findByIdAndCartId(id, cartId).filter(order -> order.status() == OrderStatus.PAID)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.ORDER_NOT_FOUND)))
                .flatMap(order -> intents.findById(order.eventId()).map(intent -> new OrderPaymentView(
                        intent.payerId(), order.paymentId(), intent.id(), intent.amountMinor(), Money.CURRENCY, "PAID"))
                        .switchIfEmpty(Mono.error(new IllegalStateException("Paid order has no payment intent"))));
    }

    public Mono<ProfileView> profile(UUID cartId) {
        return profiles.summary(cartId);
    }
}
