package com.eventledger.account.dto;

public record HealthResponse(String status, String service, boolean dbConnected) {}
