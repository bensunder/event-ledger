# Event Ledger — Java / Spring Boot

A production-grade event ledger with two services: **Account Service** (Spring Boot, port 8001) and **Event Gateway** (Spring Boot + Resilience4j, port 8000).

## Quick Start

```bash
unzip event-ledger-final.zip
cd event-ledger-final
chmod +x run-local.sh
./run-local.sh all        # install check → tests → start → smoke tests
```

Or step by step:
```bash
./run-local.sh install    # check Java 21, Maven, curl
./run-local.sh test       # run all unit/integration/WireMock tests
./run-local.sh start      # start both services
./run-local.sh smoke      # end-to-end smoke tests
./run-local.sh stop       # stop everything
```

## Prerequisites

- **Java 21** (Temurin recommended): https://adoptium.net
- **Maven 3.8+**: `brew install maven` or direct download
- **Docker** (optional, for E2E Testcontainers): https://docs.docker.com/get-docker/

## Architecture

```
Client → Event Gateway (:8000) → Account Service (:8001)
              ↓                          ↓
         H2 events DB             H2 transactions DB
```

Both services use their own independent in-memory H2 databases. No shared state.

## API

### Event Gateway
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/events` | Submit event (201 new, 208 duplicate) |
| GET | `/events/{id}` | Get event by ID (gateway DB only) |
| GET | `/events?account=X` | Get events by account (gateway DB only) |
| GET | `/health` | Health + account service status |

### Account Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/accounts/{id}/transactions` | Apply transaction |
| GET | `/accounts/{id}/balance` | Current balance |
| GET | `/accounts/{id}` | Account + transaction list |
| GET | `/health` | Health + real DB ping |

## Key Design Decisions

**Spring Data JDBC over JPA** — explicit SQL, every query visible, no lazy-loading surprises. `DATABASE_TO_LOWER=TRUE` on H2 ensures quoted lowercase identifiers from Spring Data JDBC match stored table names.

**Resilience4j — three patterns, one call path**
- `@Bulkhead` — caps concurrent calls at 25 threads
- `@Retry` — 3 attempts, exponential backoff (100ms base), **only on `ResourceAccessException`** (network failures). 4xx errors are NOT retried — they're application errors. 5xx failures trip the circuit breaker instead.
- `@CircuitBreaker` — opens at 50% failure rate over 10-call window, self-heals after 10s

**Idempotency** — two-layer guard: fast-path `findByEventId` check + `UNIQUE(event_id)` DB constraint catches concurrent duplicates. `DuplicateKeyException` is caught and the winning row returned.

**Out-of-order tolerance** — balance = `SUM(CREDIT) − SUM(DEBIT)`. Commutative. Arrival order is irrelevant. Transactions ordered by `event_timestamp` (occurrence time), never by `applied_at` (arrival time).

**Health endpoints** — `/health` runs a real `SELECT 1` DB ping (not hardcoded). Gateway `/health` calls `ping()` which bypasses the circuit breaker — a health check must never throw.

**Trace propagation** — `TraceFilter` generates UUID4 if `X-Trace-Id` absent, binds to SLF4J MDC, echoes in response. `AccountServiceClient` reads `TraceContext.get()` and sets the header on every outgoing call.

## Running Tests Individually

```bash
# Account service — 11 MockMvc tests
mvn test -pl account-service

# Gateway — 13 WireMock tests  
mvn test -pl event-gateway -Dtest=GatewayControllerTest
```
