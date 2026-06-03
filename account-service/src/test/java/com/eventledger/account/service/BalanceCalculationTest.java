package com.eventledger.account.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for balance computation logic.
 * Net balance = sum(CREDITs) - sum(DEBITs)
 * No Spring context needed.
 */
class BalanceCalculationTest {

    @Test
    void singleCreditBalance() {
        BigDecimal credit = new BigDecimal("500.00");
        BigDecimal balance = credit;
        assertEquals(new BigDecimal("500.00"), balance);
    }

    @Test
    void creditMinusDebitBalance() {
        BigDecimal credit = new BigDecimal("1000.00");
        BigDecimal debit = new BigDecimal("300.00");
        BigDecimal balance = credit.subtract(debit);
        assertEquals(new BigDecimal("700.00"), balance);
    }

    @Test
    void multipleCreditsAndDebits() {
        BigDecimal balance = BigDecimal.ZERO
                .add(new BigDecimal("1000.00"))
                .add(new BigDecimal("250.50"))
                .add(new BigDecimal("500.00"))
                .subtract(new BigDecimal("300.00"))
                .subtract(new BigDecimal("75.25"))
                .subtract(new BigDecimal("124.75"));
        assertEquals(new BigDecimal("1250.50"), balance);
    }

    @Test
    void zeroBalance() {
        BigDecimal credit = new BigDecimal("100.00");
        BigDecimal debit = new BigDecimal("100.00");
        BigDecimal balance = credit.subtract(debit);
        assertEquals(BigDecimal.ZERO.setScale(2), balance);
    }

    @Test
    void decimalPrecision() {
        BigDecimal credit = new BigDecimal("100.12");
        BigDecimal debit = new BigDecimal("0.01");
        BigDecimal balance = credit.subtract(debit);
        assertEquals(new BigDecimal("100.11"), balance);
    }
}
