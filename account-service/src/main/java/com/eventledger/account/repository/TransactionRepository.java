package com.eventledger.account.repository;

import com.eventledger.account.domain.Transaction;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends CrudRepository<Transaction, Long> {

    Optional<Transaction> findByEventId(String eventId);

    @Query("SELECT * FROM transactions WHERE account_id = :accountId ORDER BY event_timestamp ASC")
    List<Transaction> findByAccountIdOrderByEventTimestamp(@Param("accountId") String accountId);

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE 0 END), 0) -
            COALESCE(SUM(CASE WHEN type = 'DEBIT'  THEN amount ELSE 0 END), 0)
        FROM transactions
        WHERE account_id = :accountId
        """)
    Optional<BigDecimal> calculateBalance(@Param("accountId") String accountId);

    boolean existsByAccountId(String accountId);
}
