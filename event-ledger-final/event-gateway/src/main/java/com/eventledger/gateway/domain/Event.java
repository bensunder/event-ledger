package com.eventledger.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Table("events")
public class Event {

    @Id
    private Long id;
    private String eventId;
    private String accountId;
    private String type;
    private BigDecimal amount;
    private Instant eventTimestamp;
    private Instant receivedAt;
    private String traceId;
    private String status; // ACCEPTED, FAILED

    public Event() {}

    public Event(String eventId, String accountId, String type,
                 BigDecimal amount, Instant eventTimestamp, String traceId) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.eventTimestamp = eventTimestamp;
        this.traceId = traceId;
        this.receivedAt = Instant.now();
        this.status = "ACCEPTED";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
