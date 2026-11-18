package com.azentio.bulkhead.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** A payment the bank switch wants screened before it authorises it. */
public record ScreeningRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String transactionId,
        @NotBlank @Size(min = 4, max = 100) String accountToken,
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @Pattern(regexp = "\\d{4}") String merchantCategory,
        @Size(max = 100) String beneficiaryId,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @NotNull Instant timestamp) {
}
