package com.eventledger.account.controller;

import com.eventledger.account.dto.*;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {

    private final AccountService accountService;
    private final JdbcTemplate jdbcTemplate;

    public AccountController(AccountService accountService, JdbcTemplate jdbcTemplate) {
        this.accountService = accountService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody ApplyTransactionRequest req) {

        boolean[] wasDuplicate = {false};
        var response = accountService.applyTransaction(
                req.eventId(), accountId, req.type(),
                req.amount(), req.eventTimestamp(), wasDuplicate);

        return ResponseEntity
                .status(wasDuplicate[0] ? HttpStatus.ALREADY_REPORTED : HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return accountService.getBalance(accountId);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountDetailResponse getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean dbOk;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (Exception e) {
            dbOk = false;
        }
        return new HealthResponse(dbOk ? "healthy" : "degraded", "account-service", dbOk);
    }
}
