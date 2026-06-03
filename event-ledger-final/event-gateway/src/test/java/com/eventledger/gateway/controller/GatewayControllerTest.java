package com.eventledger.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayControllerTest {

    static WireMockServer wireMock;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void wireProps(DynamicPropertyRegistry registry) {
        registry.add("account-service.url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        jdbcTemplate.execute("DELETE FROM events");

        // Default stub: account service accepts any transaction
        stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"eventId":"stub","accountId":"stub","type":"CREDIT",
                             "amount":100,"eventTimestamp":"2024-01-01T00:00:00Z",
                             "appliedAt":"2024-01-01T00:00:00Z"}
                            """)));

        // Health stub
        stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"healthy\"}")));
    }

    // ── POST /events → 201 ────────────────────────────────────────────────────

    @Test
    void createEvent_returns201() throws Exception {
        sendEvent("acc-1", "CREDIT", "250.00", 201);
    }

    // ── Idempotency: same eventId → 208 ───────────────────────────────────────

    @Test
    void duplicateEvent_returns208() throws Exception {
        String eventId = UUID.randomUUID().toString();
        sendEventWithId(eventId, "acc-2", "CREDIT", "100.00", 201);
        sendEventWithId(eventId, "acc-2", "CREDIT", "100.00", 208);
        // Account service called exactly once despite two gateway requests
        verify(1, postRequestedFor(urlPathMatching("/accounts/acc-2/transactions")));
    }

    // ── X-Trace-Id forwarded to account service ────────────────────────────────

    @Test
    void traceIdForwardedToAccountService() throws Exception {
        String traceId = "trace-" + UUID.randomUUID();
        mvc.perform(post("/events")
                        .header("X-Trace-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(UUID.randomUUID().toString(), "acc-3", "CREDIT", "50.00")))
                .andExpect(status().isCreated());

        verify(1, postRequestedFor(urlPathMatching("/accounts/acc-3/transactions"))
                .withHeader("X-Trace-Id", equalTo(traceId)));
    }

    // ── Trace ID generated if absent ──────────────────────────────────────────

    @Test
    void traceIdGeneratedIfAbsent() throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(UUID.randomUUID().toString(), "acc-4", "CREDIT", "50.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));
    }

    // ── Account service 503 → Gateway returns 503 ─────────────────────────────

    @Test
    void accountServiceDown_returns503() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));
        stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"healthy\"}")));

        sendEvent("acc-5", "CREDIT", "100.00", 503);
    }

    // ── Account service network failure → 503 ─────────────────────────────────

    @Test
    void accountServiceNetworkFailure_returns503() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"healthy\"}")));

        sendEvent("acc-6", "CREDIT", "100.00", 503);
    }

    // ── GET /events/{id} reads from gateway DB (no account service call) ──────

    @Test
    void getEvent_readsFromGatewayDb() throws Exception {
        String eventId = UUID.randomUUID().toString();
        sendEventWithId(eventId, "acc-7", "CREDIT", "75.00", 201);

        // Stop the account service — GET must not call it
        wireMock.resetAll();

        mvc.perform(get("/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.accountId").value("acc-7"));

        // No calls made to account service after reset
        verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    // ── GET /events?account= ──────────────────────────────────────────────────

    @Test
    void getEventsByAccount() throws Exception {
        String acc = "acc-8-" + UUID.randomUUID();
        sendEventWithId(UUID.randomUUID().toString(), acc, "CREDIT", "100.00", 201);
        sendEventWithId(UUID.randomUUID().toString(), acc, "DEBIT",  "30.00",  201);

        mvc.perform(get("/events?account=" + acc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /events/{unknown} → 404 ───────────────────────────────────────────

    @Test
    void getUnknownEvent_returns404() throws Exception {
        mvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // ── Validation: blank accountId → 422 ────────────────────────────────────

    @Test
    void validationRejectsBlankAccountId() throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"eventId":"e1","accountId":"","type":"CREDIT",
                             "amount":10,"eventTimestamp":"2024-01-01T00:00:00Z"}
                            """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Validation: bad type → 422 ────────────────────────────────────────────

    @Test
    void validationRejectsBadType() throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"eventId":"e2","accountId":"acc","type":"TRANSFER",
                             "amount":10,"eventTimestamp":"2024-01-01T00:00:00Z"}
                            """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Health endpoint ───────────────────────────────────────────────────────

    @Test
    void healthCheck() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.dbConnected").value(true));
    }

    // ── Prometheus endpoint ───────────────────────────────────────────────────

    @Test
    void prometheusEndpoint() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gateway_events_accepted")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void sendEvent(String accountId, String type, String amount, int expectedStatus)
            throws Exception {
        sendEventWithId(UUID.randomUUID().toString(), accountId, type, amount, expectedStatus);
    }

    private void sendEventWithId(String eventId, String accountId, String type,
                                  String amount, int expectedStatus) throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(eventId, accountId, type, amount)))
                .andExpect(status().is(expectedStatus));
    }

    private String eventBody(String eventId, String accountId, String type, String amount)
            throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", type,
                "amount", new java.math.BigDecimal(amount),
                "eventTimestamp", Instant.now().toString()
        ));
    }
}
