package com.eventledger.gateway.dto;

public record GatewayHealthResponse(
    String status,
    String service,
    boolean dbConnected,
    String accountServiceStatus
) {}
