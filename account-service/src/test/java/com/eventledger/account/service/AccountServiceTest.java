package com.eventledger.account.service;

import com.eventledger.account.domain.Transaction;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock TransactionRepository repository;
    AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(repository, new SimpleMeterRegistry());
    }

    @Test
    void applyTransaction_newEvent_savesAndReturns() {
        String eventId = UUID.randomUUID().toString();
        when(repository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            t.setId(1L);
            return t;
        });

        boolean[] wasDuplicate = {false};
        var response = accountService.applyTransaction(
                eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), Instant.now(), wasDuplicate);

        assertFalse(wasDuplicate[0]);
        assertEquals(eventId, response.eventId());
        assertEquals("CREDIT", response.type());
        verify(repository).save(any());
    }

    @Test
    void applyTransaction_duplicateEvent_returnsCachedAndSetsFlag() {
        String eventId = UUID.randomUUID().toString();
        Transaction existing = new Transaction(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), Instant.now());
        existing.setId(1L);
        when(repository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        boolean[] wasDuplicate = {false};
        var response = accountService.applyTransaction(
                eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), Instant.now(), wasDuplicate);

        assertTrue(wasDuplicate[0]);
        assertEquals(eventId, response.eventId());
        verify(repository, never()).save(any());
    }

    @Test
    void getBalance_accountExists_returnsBalance() {
        String accountId = "acc-bal";
        when(repository.existsByAccountId(accountId)).thenReturn(true);
        when(repository.calculateBalance(accountId))
                .thenReturn(Optional.of(new BigDecimal("350.00")));

        var response = accountService.getBalance(accountId);
        assertEquals(accountId, response.accountId());
        assertEquals(new BigDecimal("350.00"), response.balance());
    }

    @Test
    void getBalance_accountNotFound_throws404() {
        when(repository.existsByAccountId("missing")).thenReturn(false);
        assertThrows(ResponseStatusException.class,
                () -> accountService.getBalance("missing"));
    }

    @Test
    void getAccount_returnsDetailWithTransactions() {
        String accountId = "acc-detail";
        Transaction tx = new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("200.00"), Instant.now());
        tx.setId(1L);

        when(repository.existsByAccountId(accountId)).thenReturn(true);
        when(repository.findByAccountIdOrderByEventTimestamp(accountId))
                .thenReturn(List.of(tx));
        when(repository.calculateBalance(accountId))
                .thenReturn(Optional.of(new BigDecimal("200.00")));

        var response = accountService.getAccount(accountId);
        assertEquals(accountId, response.accountId());
        assertEquals(new BigDecimal("200.00"), response.balance());
        assertEquals(1, response.transactions().size());
    }

    @Test
    void getAccount_notFound_throws404() {
        when(repository.existsByAccountId("missing")).thenReturn(false);
        assertThrows(ResponseStatusException.class,
                () -> accountService.getAccount("missing"));
    }
}
