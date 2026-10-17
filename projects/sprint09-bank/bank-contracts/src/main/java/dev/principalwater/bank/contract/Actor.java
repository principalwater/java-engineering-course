package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Actor(
        @NotBlank @Size(max = 512) String issuer,
        @NotBlank @Size(max = 128) String subject,
        @NotBlank @Size(max = 128) String login) {}
