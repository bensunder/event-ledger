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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.data.jdbc.dialect=h2",
    "spring.sql.init.mode=always",
    "management.endpoints.web.exposure.include=health,prometheus,metrics,info",
    "management.endpoint.prometheus.enabled=true",
    "management.metrics.export.prometheus.enabled=true"
})
class AccountServiceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM transactions");
    }

    @Test
    void singleCredit() throws Exception {
        postTx("acc-1", "CREDIT", "250.00", Instant.now(), 201);
        mvc.perform(get("/accounts/acc-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void creditMinusDebit() throws Exception {
        postTx("acc-2", "CREDIT", "500.00", Instant.now(), 201);
        postTx("acc-2", "DEBIT",  "200.00", Instant.now(), 201);
        mvc.perform(get("/accounts/acc-2/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.00));
    }

    @Test
    void unknownAccountBalance() throws Exception {
        mvc.perform(get("/accounts/no-such-account/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountIsolation() throws Exception {
        postTx("acc-A", "CREDIT", "100.00", Instant.now(), 201);
        postTx("acc-B", "CREDIT", "999.00", Instant.now(), 201);
        mvc.perform(get("/accounts/acc-A/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void balanceCorrectOutOfOrder() throws Exception {
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant t2 = Instant.now().minus(5,  ChronoUnit.MINUTES);
        Instant t3 = Instant.now();
        postTx("acc-3", "DEBIT",  "100.00", t3, 201);
        postTx("acc-3", "CREDIT", "500.00", t1, 201);
        postTx("acc-3", "DEBIT",  "50.00",  t2, 201);
        mvc.perform(get("/accounts/acc-3/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(350.00));
        mvc.perform(get("/accounts/acc-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.transactions[1].type").value("DEBIT"))
                .andExpect(jsonPath("$.transactions[2].type").value("DEBIT"));
    }

    @Test
    void duplicateIdempotency() throws Exception {
        String eventId = UUID.randomUUID().toString();
        postTxWithEventId(eventId, "acc-4", "CREDIT", "100.00", Instant.now(), 201);
        postTxWithEventId(eventId, "acc-4", "CREDIT", "100.00", Instant.now(), 208);
        mvc.perform(get("/accounts/acc-4/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void duplicateResponseShape() throws Exception {
        String eventId = UUID.randomUUID().toString();
        postTxWithEventId(eventId, "acc-5", "CREDIT", "75.00", Instant.now(), 201);
        mvc.perform(post("/accounts/acc-5/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(eventId, "CREDIT", "75.00", Instant.now())))
                .andExpect(status().is(208))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.type").value("CREDIT"));
    }

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

    @Test
    void healthCheck() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.dbConnected").value(true));
    }

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