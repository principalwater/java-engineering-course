package dev.principalwater.store.model;

import java.util.UUID;

public record CartChange(CartView cart, UUID checkoutKey) { }
