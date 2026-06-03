package com.eventledger.account.repository;

import com.eventledger.account.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:repotestdb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.data.jdbc.dialect=h2",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql"
})
class TransactionRepositoryTest {

    @Autowired
    TransactionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void saveAndFindByEventId() {
        String eventId = UUID.randomUUID().toString();
        var tx = new Transaction(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), Instant.now());
        repository.save(tx);

        var found = repository.findByEventId(eventId);
        assertTrue(found.isPresent());
        assertEquals("acc-1", found.get().getAccountId());
        assertEquals("CREDIT", found.get().getType());
    }

    @Test
    void findByEventId_notFound() {
        var found = repository.findByEventId("non-existent");
        assertFalse(found.isPresent());
    }

    @Test
    void calculateBalance_creditMinusDebit() {
        String accountId = "acc-bal-" + UUID.randomUUID();
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("500.00"), Instant.now()));
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("250.00"), Instant.now()));
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "DEBIT", new BigDecimal("100.00"), Instant.now()));

        var balance = repository.calculateBalance(accountId);
        assertTrue(balance.isPresent());
        assertEquals(0, new BigDecimal("650.00").compareTo(balance.get()));
    }

    @Test
    void existsByAccountId_true() {
        String accountId = "acc-exists-" + UUID.randomUUID();
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("100.00"), Instant.now()));
        assertTrue(repository.existsByAccountId(accountId));
    }

    @Test
    void existsByAccountId_false() {
        assertFalse(repository.existsByAccountId("acc-does-not-exist"));
    }

    @Test
    void findByAccountIdOrderByEventTimestamp_ordered() {
        String accountId = "acc-order-" + UUID.randomUUID();
        Instant t1 = Instant.parse("2026-01-01T09:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T12:00:00Z");
        Instant t3 = Instant.parse("2026-01-01T15:00:00Z");

        // Insert out of order
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "DEBIT", new BigDecimal("100.00"), t3));
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("500.00"), t1));
        repository.save(new Transaction(UUID.randomUUID().toString(), accountId,
                "CREDIT", new BigDecimal("250.00"), t2));

        var txs = repository.findByAccountIdOrderByEventTimestamp(accountId);
        assertEquals(3, txs.size());
        assertEquals("CREDIT", txs.get(0).getType()); // t1
        assertEquals("CREDIT", txs.get(1).getType()); // t2
        assertEquals("DEBIT",  txs.get(2).getType()); // t3
    }
}
