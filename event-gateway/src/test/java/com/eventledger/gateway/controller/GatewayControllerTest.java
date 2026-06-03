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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:gatewaytestdb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.data.jdbc.dialect=h2",
    "management.server.port=-1",
    "management.endpoints.web.exposure.include=health,prometheus,metrics,info",
    "resilience4j.ratelimiter.instances.gateway.limitForPeriod=10000",
    "resilience4j.ratelimiter.instances.gateway.limitRefreshPeriod=1s",
    "resilience4j.ratelimiter.instances.gateway.timeoutDuration=0",
    "resilience4j.retry.instances.account-service.maxAttempts=1",
    "resilience4j.retry.instances.account-service.waitDuration=10ms"
})
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

        wireMock.stubFor(WireMock.post(WireMock.urlPathMatching("/accounts/.*/transactions"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"eventId":"stub","accountId":"stub","type":"CREDIT",
                             "amount":100,"eventTimestamp":"2024-01-01T00:00:00Z",
                             "appliedAt":"2024-01-01T00:00:00Z"}
                            """)));

        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/health"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"healthy\"}")));
    }

    @Test
    void createEvent_returns201() throws Exception {
        sendEvent("acc-1", "CREDIT", "250.00", 201);
    }

    @Test
    void duplicateEvent_returns208() throws Exception {
        String eventId = UUID.randomUUID().toString();
        sendEventWithId(eventId, "acc-2", "CREDIT", "100.00", 201);
        sendEventWithId(eventId, "acc-2", "CREDIT", "100.00", 208);
        wireMock.verify(1, WireMock.postRequestedFor(
                WireMock.urlPathMatching("/accounts/acc-2/transactions")));
    }

    @Test
    void traceIdForwardedToAccountService() throws Exception {
        String traceId = "trace-" + UUID.randomUUID();
        mvc.perform(post("/events")
                        .header("X-Trace-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(UUID.randomUUID().toString(), "acc-3", "CREDIT", "50.00")))
                .andExpect(status().isCreated());

        wireMock.verify(1, WireMock.postRequestedFor(
                WireMock.urlPathMatching("/accounts/acc-3/transactions"))
                .withHeader("X-Trace-Id", WireMock.equalTo(traceId)));
    }

    @Test
    void traceIdGeneratedIfAbsent() throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody(UUID.randomUUID().toString(), "acc-4", "CREDIT", "50.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void accountServiceDown_returns503() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.post(WireMock.urlPathMatching("/accounts/.*/transactions"))
                .willReturn(WireMock.aResponse().withStatus(503)));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/health"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withBody("{\"status\":\"healthy\"}")));
        sendEvent("acc-5", "CREDIT", "100.00", 503);
    }

    @Test
    void accountServiceNetworkFailure_returns503() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.post(WireMock.urlPathMatching("/accounts/.*/transactions"))
                .willReturn(WireMock.aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/health"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withBody("{\"status\":\"healthy\"}")));
        sendEvent("acc-6", "CREDIT", "100.00", 503);
    }

    @Test
    void getEvent_readsFromGatewayDb() throws Exception {
        String eventId = UUID.randomUUID().toString();
        sendEventWithId(eventId, "acc-7", "CREDIT", "75.00", 201);
        wireMock.resetAll();
        mvc.perform(get("/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.accountId").value("acc-7"));
        wireMock.verify(0, WireMock.postRequestedFor(
                WireMock.urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void getEventsByAccount() throws Exception {
        String acc = "acc-8-" + UUID.randomUUID();
        sendEventWithId(UUID.randomUUID().toString(), acc, "CREDIT", "100.00", 201);
        sendEventWithId(UUID.randomUUID().toString(), acc, "DEBIT", "30.00", 201);
        mvc.perform(get("/events?account=" + acc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getUnknownEvent_returns404() throws Exception {
        mvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound());
    }

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

    @Test
    void healthCheck() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.dbConnected").value(true));
    }

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