package com.eventledger.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record ApplyTransactionRequest(
    @NotBlank String eventId,
    @NotBlank String accountId,
    @NotNull @Pattern(regexp = "CREDIT|DEBIT") String type,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotNull Instant eventTimestamp
) {}
