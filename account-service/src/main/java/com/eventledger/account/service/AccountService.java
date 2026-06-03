package com.eventledger.account.service;

import com.eventledger.account.domain.Transaction;
import com.eventledger.account.dto.*;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TransactionRepository repository;
    private final Counter appliedCounter;
    private final Counter duplicateCounter;
    private final Timer applyTimer;

    public AccountService(TransactionRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.appliedCounter = meterRegistry.counter("account_service.transactions.applied");
        this.duplicateCounter = meterRegistry.counter("account_service.transactions.duplicate");
        this.applyTimer = meterRegistry.timer("account_service.transaction.apply.duration");
    }

    /**
     * Apply a transaction. Returns the transaction (201 on first apply, 208 if duplicate).
     * Idempotency: UNIQUE(event_id) is the source of truth; pre-check is an optimistic fast path.
     */
    public TransactionResponse applyTransaction(
            String eventId, String accountId, String type,
            BigDecimal amount, java.time.Instant eventTimestamp,
            boolean[] wasDuplicate) {

        return applyTimer.record(() -> {
            // Fast-path idempotency check
            var existing = repository.findByEventId(eventId);
            if (existing.isPresent()) {
                duplicateCounter.increment();
                wasDuplicate[0] = true;
                return toResponse(existing.get());
            }

            try {
                var tx = new Transaction(eventId, accountId, type, amount, eventTimestamp);
                var saved = repository.save(tx);
                appliedCounter.increment();
                log.info("event_id={} account_id={} type={} amount={} applied", eventId, accountId, type, amount);
                return toResponse(saved);
            } catch (DuplicateKeyException e) {
                // Concurrent duplicate — race condition backstop
                duplicateCounter.increment();
                wasDuplicate[0] = true;
                return toResponse(repository.findByEventId(eventId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)));
            }
        });
    }

    public BalanceResponse getBalance(String accountId) {
        if (!repository.existsByAccountId(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
        }
        var balance = repository.calculateBalance(accountId).orElse(BigDecimal.ZERO);
        return new BalanceResponse(accountId, balance);
    }

    public AccountDetailResponse getAccount(String accountId) {
        if (!repository.existsByAccountId(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
        }
        var txs = repository.findByAccountIdOrderByEventTimestamp(accountId)
                .stream().map(this::toResponse).toList();
        var balance = repository.calculateBalance(accountId).orElse(BigDecimal.ZERO);
        return new AccountDetailResponse(accountId, balance, txs);
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getEventId(), t.getAccountId(),
                t.getType(), t.getAmount(), t.getEventTimestamp(), t.getAppliedAt());
    }
}
