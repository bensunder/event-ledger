package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailResponse(
    String accountId,
    BigDecimal balance,
    List<TransactionResponse> transactions
) {}
