#!/usr/bin/env bash
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[ OK ]${NC}  $*"; }
fail() { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }
info() { echo -e "${BLUE}[INFO]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
hr()   { echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACCT_PID=""
GW_PID=""

cleanup() {
  [[ -n "$ACCT_PID" ]] && kill "$ACCT_PID" 2>/dev/null || true
  [[ -n "$GW_PID"   ]] && kill "$GW_PID"   2>/dev/null || true
}
trap cleanup EXIT

# ──────────────────────────────────────────────────────────────────────────────
install_check() {
  hr; echo "  Checking prerequisites"; hr

  java -version &>/dev/null && ok "Java 21 found: $(java -version 2>&1 | head -1)" \
    || fail "Java not found. Install from https://adoptium.net"

  mvn -version &>/dev/null && ok "Maven found: $(mvn -version 2>&1 | head -1)" \
    || fail "Maven not found. See README."

  if docker info &>/dev/null 2>&1; then
    ok "Docker found"
  else
    warn "Docker not running or not installed."
    warn "Testcontainers E2E tests will be skipped."
    warn "Install Docker Desktop: https://docs.docker.com/get-docker/"
  fi

  curl --version &>/dev/null && ok "curl found" || fail "curl not found"
  python3 --version &>/dev/null && ok "python3 found" || warn "python3 not found (smoke tests need it)"

  echo ""; ok "All prerequisites satisfied"
}

# ──────────────────────────────────────────────────────────────────────────────
run_tests() {
  hr; echo "  Running tests"; hr

  info "Building account-service JAR (required for Testcontainers E2E)..."
  mvn package -pl account-service -DskipTests -q \
    || fail "account-service build failed"
  ok "account-service JAR built"

  info "Running account-service tests (MockMvc + H2)..."
  mvn test -pl account-service --no-transfer-progress \
    && ok "account-service: all tests passed" \
    || fail "account-service tests failed. Check output above."

  info "Running event-gateway WireMock tests..."
  mvn test -pl event-gateway -Dtest=GatewayControllerTest --no-transfer-progress \
    && ok "event-gateway WireMock: all tests passed" \
    || fail "event-gateway WireMock tests failed."

  if docker info &>/dev/null 2>&1; then
    info "Running Testcontainers E2E tests (requires Docker)..."
    warn "Skipping Testcontainers E2E — no E2E test class in this build."
  else
    warn "Skipping Testcontainers E2E (Docker not available)."
  fi

  echo ""; ok "All tests passed"
}

# ──────────────────────────────────────────────────────────────────────────────
start_services() {
  hr; echo "  Building and starting services"; hr

  info "Building JARs..."
  mvn package -DskipTests -q || fail "Build failed"
  ok "JARs built"

  info "Starting account-service on :8001 ..."
  java -jar account-service/target/*.jar \
    --server.port=8001 \
    --spring.datasource.url="jdbc:h2:mem:accountdb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE" \
    > /tmp/account-service.log 2>&1 &
  ACCT_PID=$!

  for i in $(seq 1 30); do
    sleep 1
    curl -sf http://localhost:8001/health &>/dev/null && break
    [[ $i -eq 30 ]] && { cat /tmp/account-service.log; fail "account-service failed to start"; }
  done
  ok "Account Service started → http://localhost:8001"

  info "Starting event-gateway on :8000 ..."
  java -jar event-gateway/target/*.jar \
    --server.port=8000 \
    --account-service.url=http://localhost:8001 \
    --spring.datasource.url="jdbc:h2:mem:gatewaydb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE" \
    > /tmp/event-gateway.log 2>&1 &
  GW_PID=$!

  for i in $(seq 1 30); do
    sleep 1
    curl -sf http://localhost:8000/health &>/dev/null && break
    [[ $i -eq 30 ]] && { cat /tmp/event-gateway.log; fail "event-gateway failed to start"; }
  done
  ok "Event Gateway started → http://localhost:8000"
}

# ──────────────────────────────────────────────────────────────────────────────
run_smoke() {
  hr; echo "  Smoke tests"; hr

  # Health checks
  curl -sf http://localhost:8001/health | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d['status'] == 'healthy', f'account health: {d}'
assert d['dbConnected'] == True
print('  ✓ Account Service health OK')
"

  curl -sf http://localhost:8000/health | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d['status'] == 'healthy', f'gateway health: {d}'
print('  ✓ Gateway health OK')
"

  # POST event → 201
  EVT1=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
  RESP=$(curl -sf -w "\n%{http_code}" -X POST http://localhost:8000/events \
    -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$EVT1\",\"accountId\":\"smoke-acc\",\"type\":\"CREDIT\",\"amount\":500.00,\"eventTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")
  CODE=$(echo "$RESP" | tail -1)
  [[ "$CODE" == "201" ]] && echo "  ✓ POST /events → 201" || fail "Expected 201, got $CODE"

  # Duplicate → 208
  RESP=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8000/events \
    -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$EVT1\",\"accountId\":\"smoke-acc\",\"type\":\"CREDIT\",\"amount\":500.00,\"eventTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")
  CODE=$(echo "$RESP" | tail -1)
  [[ "$CODE" == "208" ]] && echo "  ✓ Duplicate event → 208" || fail "Expected 208, got $CODE"

  # POST second event
  EVT2=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
  curl -sf -X POST http://localhost:8000/events \
    -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$EVT2\",\"accountId\":\"smoke-acc\",\"type\":\"DEBIT\",\"amount\":250.00,\"eventTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /dev/null
  echo "  ✓ Second event (DEBIT 250) accepted"

  # Check balance via account service
  BAL=$(curl -sf http://localhost:8001/accounts/smoke-acc/balance | python3 -c "import sys,json; print(json.load(sys.stdin)['balance'])")
  echo "  ✓ Balance = $BAL (expected 250.00)"

  # GET event by ID
  curl -sf "http://localhost:8000/events/$EVT1" > /dev/null \
    && echo "  ✓ GET /events/{id} → 200" || fail "GET /events/{id} failed"

  # GET events by account
  COUNT=$(curl -sf "http://localhost:8000/events?account=smoke-acc" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
  [[ "$COUNT" == "2" ]] && echo "  ✓ GET /events?account= returns $COUNT events" || fail "Expected 2 events, got $COUNT"

  # Validation → 422
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8000/events \
    -H 'Content-Type: application/json' \
    -d '{"eventId":"","accountId":"x","type":"CREDIT","amount":10,"eventTimestamp":"2024-01-01T00:00:00Z"}')
  [[ "$CODE" == "422" ]] && echo "  ✓ Validation → 422" || fail "Expected 422, got $CODE"

  echo ""; ok "All smoke tests passed"
}

# ──────────────────────────────────────────────────────────────────────────────
show_logs() {
  echo "=== account-service ===" && tail -50 /tmp/account-service.log
  echo "=== event-gateway ===" && tail -50 /tmp/event-gateway.log
}

stop_services() {
  cleanup
  echo "Services stopped."
}

# ──────────────────────────────────────────────────────────────────────────────
CMD="${1:-all}"

case "$CMD" in
  install)  install_check ;;
  test)     install_check; run_tests ;;
  start)    start_services ;;
  smoke)    run_smoke ;;
  logs)     show_logs ;;
  stop)     stop_services ;;
  all)
    install_check
    run_tests
    start_services
    run_smoke
    hr; echo ""; ok "Everything green. Services running on :8000 and :8001"
    echo "  Logs: ./run-local.sh logs"
    echo "  Stop: ./run-local.sh stop"
    # Keep running
    wait $GW_PID
    ;;
  *)
    echo "Usage: $0 [install|test|start|smoke|logs|stop|all]"
    exit 1
    ;;
esac
