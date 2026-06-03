package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.config.TraceContext;
import com.eventledger.gateway.dto.*;
import com.eventledger.gateway.service.GatewayService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class GatewayController {

    private final GatewayService gatewayService;
    private final AccountServiceClient accountServiceClient;
    private final JdbcTemplate jdbcTemplate;

    public GatewayController(GatewayService gatewayService,
                              AccountServiceClient accountServiceClient,
                              JdbcTemplate jdbcTemplate) {
        this.gatewayService = gatewayService;
        this.accountServiceClient = accountServiceClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/events")
    @RateLimiter(name = "gateway", fallbackMethod = "rateLimitFallback")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest req) {
        boolean[] wasDuplicate = {false};
        String traceId = TraceContext.get();
        var response = gatewayService.submitEvent(req, traceId, wasDuplicate);
        return ResponseEntity
                .status(wasDuplicate[0] ? HttpStatus.ALREADY_REPORTED : HttpStatus.CREATED)
                .body(response);
    }

    public ResponseEntity<EventResponse> rateLimitFallback(
            CreateEventRequest req, Throwable t) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    @GetMapping("/events/{eventId}")
    public EventResponse getEvent(@PathVariable String eventId) {
        return gatewayService.getEvent(eventId);
    }

    @GetMapping("/events")
    public List<EventResponse> getEventsByAccount(@RequestParam String account) {
        return gatewayService.getEventsByAccount(account);
    }

    @GetMapping("/health")
    public GatewayHealthResponse health() {
        boolean dbOk;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (Exception e) {
            dbOk = false;
        }
        boolean acctOk = accountServiceClient.ping();
        return new GatewayHealthResponse(
                dbOk ? "healthy" : "degraded",
                "event-gateway",
                dbOk,
                acctOk ? "UP" : "DOWN");
    }
}