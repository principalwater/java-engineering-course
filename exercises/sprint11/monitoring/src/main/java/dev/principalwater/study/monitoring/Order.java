package dev.principalwater.study.monitoring;

import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    static final int USERNAME_MAX_LENGTH = 64;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = USERNAME_MAX_LENGTH)
    private String username;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType orderType;

    protected Order() {}

    Order(String username, OrderType orderType) {
        this.username = username;
        this.orderType = orderType;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public OrderType getOrderType() { return orderType; }
}
