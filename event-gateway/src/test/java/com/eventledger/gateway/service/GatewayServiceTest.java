package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.CreateEventRequest;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayService using Mockito.
 * No Spring context — fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class GatewayServiceTest {

    @Mock EventRepository eventRepository;
    @Mock AccountServiceClient accountServiceClient;

    GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        gatewayService = new GatewayService(
                eventRepository, accountServiceClient, new SimpleMeterRegistry());
    }

    @Test
    void submitEvent_newEvent_callsAccountService() {
        String eventId = UUID.randomUUID().toString();
        var req = new CreateEventRequest(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), "USD", Instant.now());

        when(eventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean[] wasDuplicate = {false};
        gatewayService.submitEvent(req, "trace-123", wasDuplicate);

        assertFalse(wasDuplicate[0]);
        verify(accountServiceClient).applyTransaction(
                eq("acc-1"), eq(eventId), eq("CREDIT"),
                eq(new BigDecimal("100.00")), any());
    }

    @Test
    void submitEvent_duplicateEvent_doesNotCallAccountService() {
        String eventId = UUID.randomUUID().toString();
        var req = new CreateEventRequest(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), "USD", Instant.now());

        Event existing = new Event(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), Instant.now(), "trace-abc");
        when(eventRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        boolean[] wasDuplicate = {false};
        gatewayService.submitEvent(req, "trace-123", wasDuplicate);

        assertTrue(wasDuplicate[0]);
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void submitEvent_accountServiceDown_throwsServiceUnavailable() {
        String eventId = UUID.randomUUID().toString();
        var req = new CreateEventRequest(eventId, "acc-1", "CREDIT",
                new BigDecimal("100.00"), "USD", Instant.now());

        when(eventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new AccountServiceUnavailableException("down"))
                .when(accountServiceClient).applyTransaction(any(), any(), any(), any(), any());

        boolean[] wasDuplicate = {false};
        assertThrows(ResponseStatusException.class,
                () -> gatewayService.submitEvent(req, "trace-123", wasDuplicate));
    }

    @Test
    void getEvent_notFound_throws404() {
        when(eventRepository.findByEventId("missing")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> gatewayService.getEvent("missing"));
    }
}
