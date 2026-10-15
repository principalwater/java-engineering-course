package dev.principalwater.store.model;

import jakarta.validation.constraints.*;

public record ProductInput(
        @NotBlank @Size(max = StoreLimits.SKU_LENGTH)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String sku,
        @NotBlank @Size(max = StoreLimits.TITLE_LENGTH) String title,
        @NotBlank @Size(max = StoreLimits.DESCRIPTION_LENGTH) String description,
        @NotNull ImageAsset image,
        @NotNull @Min(1) @Max(StoreLimits.MAX_PRICE_RUBLES) Long price) {
    public ProductInput {
        sku = sku == null ? null : sku.strip();
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}
