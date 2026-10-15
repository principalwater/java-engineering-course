package dev.principalwater.store.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartMutationInput(@Positive long id, @NotNull CartAction action, Integer quantity) {
}
