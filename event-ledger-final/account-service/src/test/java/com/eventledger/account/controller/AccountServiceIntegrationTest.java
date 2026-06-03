package com.eventledger.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM transactions");
    }

    // ── Single CREDIT ──────────────────────────────────────────────────────────

    @Test
    void singleCredit() throws Exception {
        postTx("acc-1", "CREDIT", "250.00", Instant.now(), 201);

        mvc.perform(get("/accounts/acc-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    // ── CREDIT minus DEBIT ─────────────────────────────────────────────────────

    @Test
    void creditMinusDebit() throws Exception {
        postTx("acc-2", "CREDIT", "500.00", Instant.now(), 201);
        postTx("acc-2", "DEBIT",  "200.00", Instant.now(), 201);

        mvc.perform(get("/accounts/acc-2/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.00));
    }

    // ── Unknown account → 404 ──────────────────────────────────────────────────

    @Test
    void unknownAccountBalance() throws Exception {
        mvc.perform(get("/accounts/no-such-account/balance"))
                .andExpect(status().isNotFound());
    }

    // ── Account isolation ──────────────────────────────────────────────────────

    @Test
    void accountIsolation() throws Exception {
        postTx("acc-A", "CREDIT", "100.00", Instant.now(), 201);
        postTx("acc-B", "CREDIT", "999.00", Instant.now(), 201);

        mvc.perform(get("/accounts/acc-A/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    // ── Out-of-order tolerance ─────────────────────────────────────────────────

    @Test
    void balanceCorrectOutOfOrder() throws Exception {
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant t2 = Instant.now().minus(5,  ChronoUnit.MINUTES);
        Instant t3 = Instant.now();

        // Send in reverse order
        postTx("acc-3", "DEBIT",  "100.00", t3, 201);
        postTx("acc-3", "CREDIT", "500.00", t1, 201);
        postTx("acc-3", "DEBIT",  "50.00",  t2, 201);

        // Balance is always SUM(CREDIT) - SUM(DEBIT) regardless of arrival order
        mvc.perform(get("/accounts/acc-3/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(350.00));

        // Transactions ordered by event_timestamp, not applied_at
        mvc.perform(get("/accounts/acc-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.transactions[1].type").value("DEBIT"))
                .andExpect(jsonPath("$.transactions[2].type").value("DEBIT"));
    }

    // ── Idempotency: same event_id returns 208 ─────────────────────────────────

    @Test
    void duplicateIdempotency() throws Exception {
        String eventId = UUID.randomUUID().toString();
        postTxWithEventId(eventId, "acc-4", "CREDIT", "100.00", Instant.now(), 201);
        postTxWithEventId(eventId, "acc-4", "CREDIT", "100.00", Instant.now(), 208);

        // Balance is 100, not 200
        mvc.perform(get("/accounts/acc-4/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    // ── Duplicate response shape ───────────────────────────────────────────────

    @Test
    void duplicateResponseShape() throws Exception {
        String eventId = UUID.randomUUID().toString();
        postTxWithEventId(eventId, "acc-5", "CREDIT", "75.00", Instant.now(), 201);
        // Second call returns same event body
        mvc.perform(post("/accounts/acc-5/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(eventId, "CREDIT", "75.00", Instant.now())))
                .andExpect(status().is(208))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.type").value("CREDIT"));
    }

    // ── Validation: missing field → 422 ───────────────────────────────────────

    @Test
    void validationRejectsBlankEventId() throws Exception {
        mvc.perform(post("/accounts/acc-6/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"eventId":"","accountId":"acc-6","type":"CREDIT",
                             "amount":100,"eventTimestamp":"2024-01-01T00:00:00Z"}
                            """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Validation: bad type → 422 ─────────────────────────────────────────────

    @Test
    void validationRejectsBadType() throws Exception {
        mvc.perform(post("/accounts/acc-7/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"eventId":"evt-x","accountId":"acc-7","type":"INVALID",
                             "amount":100,"eventTimestamp":"2024-01-01T00:00:00Z"}
                            """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Validation: zero amount → 422 ─────────────────────────────────────────

    @Test
    void validationRejectsZeroAmount() throws Exception {
        mvc.perform(post("/accounts/acc-8/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"eventId":"evt-z","accountId":"acc-8","type":"CREDIT",
                             "amount":0,"eventTimestamp":"2024-01-01T00:00:00Z"}
                            """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Health endpoint ────────────────────────────────────────────────────────

    @Test
    void healthCheck() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.dbConnected").value(true));
    }

    // ── Trace ID echoed in response ────────────────────────────────────────────

    @Test
    void traceIdEchoed() throws Exception {
        String traceId = "test-trace-" + UUID.randomUUID();
        mvc.perform(post("/accounts/acc-9/transactions")
                        .header("X-Trace-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), "CREDIT", "50.00", Instant.now())))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", traceId));
    }

    // ── Prometheus endpoint ────────────────────────────────────────────────────

    @Test
    void prometheusEndpoint() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("account_service_transactions_applied")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void postTx(String accountId, String type, String amount,
                        Instant ts, int expectedStatus) throws Exception {
        postTxWithEventId(UUID.randomUUID().toString(), accountId, type, amount, ts, expectedStatus);
    }

    private void postTxWithEventId(String eventId, String accountId, String type,
                                   String amount, Instant ts, int expectedStatus) throws Exception {
        mvc.perform(post("/accounts/" + accountId + "/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(eventId, type, amount, ts)))
                .andExpect(status().is(expectedStatus));
    }

    private String body(String eventId, String type, String amount, Instant ts) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "accountId", "placeholder",
                "type", type,
                "amount", new java.math.BigDecimal(amount),
                "eventTimestamp", ts.toString()
        ));
    }
}
