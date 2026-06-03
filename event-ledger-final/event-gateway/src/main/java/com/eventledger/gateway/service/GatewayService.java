package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.CreateEventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final Counter acceptedCounter;
    private final Counter failedCounter;

    public GatewayService(EventRepository eventRepository,
                          AccountServiceClient accountServiceClient,
                          MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.acceptedCounter = meterRegistry.counter("gateway.events.accepted");
        this.failedCounter = meterRegistry.counter("gateway.events.failed");
    }

    public EventResponse submitEvent(CreateEventRequest req, String traceId, boolean[] wasDuplicate) {
        // Gateway idempotency check
        var existing = eventRepository.findByEventId(req.eventId());
        if (existing.isPresent()) {
            wasDuplicate[0] = true;
            return toResponse(existing.get());
        }

        // Save event record
        var event = new Event(req.eventId(), req.accountId(), req.type(),
                req.amount(), req.eventTimestamp(), traceId);
        event = eventRepository.save(event);

        // Forward to Account Service
        try {
            accountServiceClient.applyTransaction(
                    req.accountId(), req.eventId(), req.type(),
                    req.amount(), req.eventTimestamp());
            acceptedCounter.increment();
            log.info("event_id={} forwarded to account-service", req.eventId());
        } catch (AccountServiceUnavailableException e) {
            failedCounter.increment();
            event.setStatus("FAILED");
            eventRepository.save(event);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account service unavailable");
        }

        return toResponse(event);
    }

    public EventResponse getEvent(String eventId) {
        return eventRepository.findByEventId(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event not found: " + eventId));
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestamp(accountId)
                .stream().map(this::toResponse).toList();
    }

    private EventResponse toResponse(Event e) {
        return new EventResponse(e.getId(), e.getEventId(), e.getAccountId(),
                e.getType(), e.getAmount(), e.getEventTimestamp(),
                e.getReceivedAt(), e.getTraceId(), e.getStatus());
    }
}
