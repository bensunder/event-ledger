package com.eventledger.gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
    Long id,
    String eventId,
    String accountId,
    String type,
    BigDecimal amount,
    Instant eventTimestamp,
    Instant receivedAt,
    String traceId,
    String status
) {}
