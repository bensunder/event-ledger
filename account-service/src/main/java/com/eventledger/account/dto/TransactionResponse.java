package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
    Long id,
    String eventId,
    String accountId,
    String type,
    BigDecimal amount,
    Instant eventTimestamp,
    Instant appliedAt
) {}
