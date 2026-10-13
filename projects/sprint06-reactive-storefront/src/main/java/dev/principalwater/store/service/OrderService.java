package dev.principalwater.store.service;

import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public List<OrderView> list(UUID cartId) {
        return orders.findByCartIdOrderByCreatedAtDescIdDesc(cartId).stream().map(OrderView::from).toList();
    }

    public OrderView get(long id, UUID cartId) {
        return orders.findByIdAndCartId(id, cartId).map(OrderView::from)
                .orElseThrow(() -> new StoreException(StoreError.ORDER_NOT_FOUND));
    }
}
