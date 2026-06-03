package com.eventledger.gateway.client;

import com.eventledger.gateway.config.TraceContext;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String accountServiceUrl;

    public AccountServiceClient(
            RestTemplate restTemplate,
            @Value("${account-service.url}") String accountServiceUrl) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
    }

    /**
     * Annotation order is execution order:
     * Bulkhead → Retry → CircuitBreaker
     *
     * - Bulkhead: limits concurrent calls to 25 threads
     * - Retry: 3 attempts with exponential backoff, ONLY on ResourceAccessException (network)
     *          NOT on HttpClientErrorException (4xx = application error, don't retry)
     * - CircuitBreaker: trips at 50% failure rate, resets after 10s
     *          5xx failures count toward opening the circuit (via recordExceptions config)
     */
    @Bulkhead(name = "account-service")
    @Retry(name = "account-service", fallbackMethod = "applyTransactionFallback")
    @CircuitBreaker(name = "account-service", fallbackMethod = "applyTransactionFallback")
    public Map<String, Object> applyTransaction(
            String accountId, String eventId, String type,
            BigDecimal amount, Instant eventTimestamp) {

        var body = Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", type,
                "amount", amount,
                "eventTimestamp", eventTimestamp.toString()
        );

        var response = restTemplate.exchange(
                accountServiceUrl + "/accounts/" + accountId + "/transactions",
                HttpMethod.POST,
                buildEntity(body),
                Map.class);

        return response.getBody();
    }

    @Bulkhead(name = "account-service")
    @CircuitBreaker(name = "account-service", fallbackMethod = "pingFallback")
    public boolean ping() {
        try {
            restTemplate.getForEntity(accountServiceUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    public Map<String, Object> applyTransactionFallback(
            String accountId, String eventId, String type,
            BigDecimal amount, Instant eventTimestamp, Throwable t) {
        log.warn("Account service unavailable for account={} eventId={}: {}", accountId, eventId, t.getMessage());
        throw new AccountServiceUnavailableException("Account service unavailable: " + t.getMessage());
    }

    @SuppressWarnings("unused")
    public boolean pingFallback(Throwable t) {
        return false;
    }

    private HttpEntity<Map<String, Object>> buildEntity(Map<String, Object> body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String traceId = TraceContext.get();
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        return new HttpEntity<>(body, headers);
    }
}
