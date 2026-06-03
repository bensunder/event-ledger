package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.Event;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends CrudRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    @Query("SELECT * FROM events WHERE account_id = :accountId ORDER BY event_timestamp ASC")
    List<Event> findByAccountIdOrderByEventTimestamp(@Param("accountId") String accountId);
}
