# Payment Gateway

A production-grade payment orchestrator with auth/capture flow, 7 Kotlin/Spring Boot microservices, PCI-modeled security zones, CDC-driven double-entry ledger, and comprehensive observability. Built entirely with AI-assisted development using [Claude Code](https://claude.ai/code).

![Kotlin](https://img.shields.io/badge/Kotlin-JDK_17-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![Kafka](https://img.shields.io/badge/Kafka-KRaft-231F20?logo=apachekafka&logoColor=white)
![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?logo=docker&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?logo=grafana&logoColor=white)

---

## What This Project Demonstrates

- **Financial consistency** -- CDC-driven double-entry ledger with debit=credit invariant validated across 3,000+ concurrent payment intents
- **Concurrency correctness** -- Pessimistic locking on aggregate root, Redis + DB idempotency, terminal-state guards; zero race conditions under 200 concurrent threads
- **Resilience** -- Resilience4j circuit breakers (failure + slow-call detection), bulkhead-gated DB fallback, transactional outbox with at-least-once delivery, dead-letter auto-retry with exponential backoff
- **PCI-modeled security** -- Card services isolated to internal Docker network (no external ports), AES-256-GCM encryption with versioned key rotation, HMAC-SHA256 webhook verification, PCI audit logging
- **Observability** -- 3 auto-provisioned Grafana dashboards, 10+ Prometheus alert rules, correlation IDs across all 7 services, business-level SLIs (auth success rate, confirm P99)
- **Performance** -- 100 TPS sustained (3,100 intents in 30s), create P99 250ms, confirm P50 1s, connection-pooled HTTP clients, tuned HikariCP/PostgreSQL, Caffeine caching, cursor-based pagination
- **Comprehensive testing** -- 27 integration tests across 6 modules covering concurrency, security, load, ledger consistency, and observability

---

## Architecture

```
                              Client
                                |
                    +-----------+-----------+
                    |     Non-PCI Zone      |
                    |                       |
                    |  acquirer-core  :8080  | ---------> merchant-service :8087
                    |  (orchestrator)        |
                    |       |          |     |
                    |       |          +-----|----------> external-gateway :8081
                    |       |     webhook    |                  |
                    |       |          +-----|<-----------------+
                    |       |               |
                    +-------|---------------|+
                            |               |
                Debezium CDC|via Kafka       | RPC (internal)
                            v               |
                    +-------------+         |
                    | ledger-     |         |
                    | service     | :8082   |
                    +-------------+         |
                                            |
                    +------- PCI Zone ------+---+
                    |     (internal only)        |
                    |                            |
                    |  card-vault-service  :8086  |  AES-256-GCM encrypted PAN
                    |  token-service       :8084  |  Payment method references
                    |  card-auth-service   :8085  |  Auth/capture orchestration
                    |       |                    |
                    |       +----> gateway       |
                    +----------------------------+

                +-----------+  +-------+  +-------+
                |PostgreSQL |  | Redis |  | Kafka |
                |  16 (x6)  |  |   7   |  | KRaft |
                +-----------+  +-------+  +-------+
```

PCI zone services have no external port mappings -- accessible only within the Docker network.

---

## System Requirements

| | Minimum | Recommended |
|---|---|---|
| **CPU** | 2 cores | 4+ cores |
| **RAM** | 6 GB | 8+ GB |
| **Disk** | 10 GB | 20 GB |
| **Docker** | Docker Compose v2, 4 GB allocated | Docker Compose v2, 6+ GB allocated |
| **OS** | macOS 12+ / Linux (x86_64 or arm64) | macOS 14+ / Linux |

**Minimum** runs all 15 containers but may see degraded throughput (~30 TPS) and longer startup times. **Recommended** sustains 100 TPS with headroom for Grafana, Prometheus, and CDC processing.

For macOS with Colima: `colima start --cpu 4 --memory 8`

---

## Quick Start

**Prerequisites:** Docker Desktop (Docker Compose v2) or Colima

```bash
# Start the full stack (~2 min first build, ~30s subsequent)
docker-compose up -d

# Wait for services to become healthy (~30s)
curl -s http://localhost:8080/actuator/health | jq .status
```

### Try a payment flow

```bash
# 1. Create a merchant
curl -s -X POST http://localhost:8087/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name": "Demo Merchant"}' | jq .

# 2. Create a payment intent (replace MERCHANT_ID)
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-001" \
  -d '{"merchantId": "MERCHANT_ID", "amount": 5000, "currency": "USD"}' | jq .

# 3. Confirm with card details (replace PI_ID)
curl -s -X POST http://localhost:8080/v1/payment_intents/PI_ID/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "cardNumber": "4242424242424242",
    "cardholderName": "Test User",
    "expiryMonth": 12,
    "expiryYear": 2030,
    "cvc": "123"
  }' | jq .

# 4. Poll for authorization (wait 2-3s for async webhook)
curl -s http://localhost:8080/v1/payment_intents/PI_ID | jq .status

# 5. Capture (once status is "authorized")
curl -s -X POST http://localhost:8080/v1/payment_intents/PI_ID/capture | jq .
```

The mock gateway processes asynchronously: 70% success, 20% failure, 10% timeout.

See [docs/test.md](docs/test.md) for the full manual testing guide. Grafana dashboards available at [localhost:3000](http://localhost:3000) (admin/admin).

---

## Payment Lifecycle

```
REQUIRES_CONFIRMATION
  |-- [confirm] --> AUTHORIZED  (webhook success, 70%)
  |                    |-- [capture] --> CAPTURED
  |-- [confirm] --> FAILED      (webhook failure, 20%)
  |-- [timeout] --> EXPIRED     (scheduler, 180s)
```

Terminal states are irreversible. Late webhooks on expired/failed intents are silently dropped.

---

## Key Design Decisions

**Why CDC for the ledger instead of REST calls?**
Debezium reads the PostgreSQL WAL and publishes to Kafka. The ledger-service consumes events asynchronously. If the ledger is down, payments still process -- the ledger catches up on recovery. Dedup via unique constraint on `(paymentIntentId, eventType, entryType, account)`. [Details](docs/learnings/05-cdc-and-events.md)

**Why pessimistic locking on every mutation?**
Every state change acquires `SELECT ... FOR UPDATE` on the PaymentIntent before reading or writing. This eliminates double-confirm, double-capture, and webhook-vs-scheduler races. Lock timeout capped at 3 seconds to prevent connection pool exhaustion under contention. [Details](docs/learnings/01-concurrency-and-locking.md)

**Why the transactional outbox for gateway dispatch?**
Dispatching inside a transaction risks silent payment loss on JVM crash. Instead: save `InternalAttempt` with `dispatched=false`, commit, then dispatch async. A scheduler retries undispatched attempts every 3 seconds. At-least-once delivery with zero risk of lost payments. [Details](docs/learnings/02-transaction-patterns.md)

**Why circuit breakers need slow-call detection?**
A degraded service responding at 4999ms (just under the 5s timeout) never trips a failure-only breaker. All threads pile up waiting. Slow-call threshold: if 80% of calls exceed 2 seconds, the circuit opens immediately. [Details](docs/learnings/09-resilience.md)

**Why PCI isolation at the Docker network level?**
Card-handling services have no `ports:` section in docker-compose. Even if a developer adds a debug endpoint, it can't be reached externally. Infrastructure-enforced, not code-enforced. [Details](docs/learnings/08-pci-architecture.md)

**Why dual-key webhook secret rotation?**
Rotating an HMAC secret requires updating signer and verifier simultaneously. The verifier accepts both current and previous secrets during the transition. The signer switches atomically. Zero-downtime rotation. [Details](docs/learnings/04-security.md)

**Why cursor pagination over offset?**
Offset pagination is O(n) -- PostgreSQL scans and discards all preceding rows. Keyset pagination using `(created_at, id)` is O(1) regardless of depth. [Details](docs/learnings/10-api-design.md)

**Why dead-letter auto-retry with backoff?**
At 300+ TPS, transient CDC failures produce 30+ dead letters/day. Manual retry doesn't scale. Exponential backoff (1m, 5m, 30m, 2h, 12h) with max 5 retries. [Details](docs/learnings/05-cdc-and-events.md)

---

## Confirm Flow (6-hop RPC chain)

```
Client --> acquirer-core --> card-vault     (encrypt PAN)
                         --> token-service  (create payment method)
                         --> card-auth      (dispatch authorization)
                                --> card-vault     (read encrypted PAN)
                                --> token-service  (read payment method)
                                --> external-gateway (authorize)
Client <-- 200 OK (async)
                                      ~~~~ PCI ZONE ~~~~

[1-5s later: async webhook]

external-gateway --> acquirer-core (webhook callback)
                 --> card-auth     (update InternalAttempt)
                 --> DB: PaymentIntent status updated

[async via CDC]

PostgreSQL WAL --> Debezium --> Kafka --> ledger-service (double-entry posting)
```

---

## Services

| Service | Zone | Port | Database | Purpose |
|---------|------|------|----------|---------|
| acquirer-core-backend | Non-PCI | 8080 | acquirer_core | Payment orchestration, webhook routing |
| external-payment-gateway | Non-PCI | 8081 | -- | Mock gateway with probabilistic outcomes |
| ledger-service | Non-PCI | 8082 | ledger | CDC-driven double-entry ledger |
| merchant-service | Non-PCI | 8087 | merchant_service | Merchant CRUD |
| card-vault-service | PCI | internal | card_vault | AES-256-GCM encrypted PAN storage |
| token-service | PCI | internal | token_service | Payment method references (brand, last4) |
| card-auth-service | PCI | internal | card_auth | Auth/capture orchestration, gateway dispatch |

---

## Testing

27 integration tests across 6 modules:

| Category | Tests | What it verifies |
|----------|-------|-----------------|
| Payments | 7 | CRUD, state machine guards, input validation (create + confirm DTOs), merchant validation, capture error paths |
| Concurrency | 5 | Double-confirm/capture races, distributed locks, 80-thread confirm contention, idempotency, duplicate confirm guard |
| Security | 6 | HMAC verification, PCI port isolation, webhook replay/late webhook rejection, timestamp tolerance |
| Load | 4 | 100 TPS sustained for 30s, outbox dispatch flag, dispatch-retry scheduler, auth expiry |
| Ledger | 2 | Debit=credit invariant across 3,000+ intents, dead-letter API |
| Observability | 5 | Correlation IDs, business metrics, HikariCP pool, cursor pagination, health checks |

```bash
# Full stack must be running first
docker-compose up -d

# Run all tests (including sustained load)
python3 -m api_tests.run_all

# Quick mode (skip sustained load test)
python3 -m api_tests.run_all --quick

# Stress only (sustained load + ledger)
python3 -m api_tests.run_all --stress
```

### Measured Performance (4 CPU / 8 GB RAM)

| Metric | Value |
|--------|-------|
| Sustained throughput | 100 TPS (3,100 intents in 30s) |
| Create latency | P50 24ms, P95 159ms, P99 250ms |
| Confirm latency | P50 1,008ms, P95 1,459ms, P99 1,796ms |
| Webhook drain time | 23s for 3,100 intents |
| Terminal coverage | 100% (all intents reach terminal state) |
| Ledger consistency | Debit = Credit across all intents |
| Concurrent confirm (80 threads) | Exactly 1 PaymentAttempt, P50 lock rejection 71ms |
| Idle memory (all services) | ~3.6 GB total |

---

## Monitoring

| URL | What |
|-----|------|
| [localhost:3000](http://localhost:3000) | Grafana (admin/admin) -- 3 auto-provisioned dashboards |
| [localhost:9090](http://localhost:9090) | Prometheus -- 10+ alert rules pre-configured |
| [localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | Swagger UI -- acquirer-core-backend |
| [localhost:8087/swagger-ui.html](http://localhost:8087/swagger-ui.html) | Swagger UI -- merchant-service |

**Grafana dashboards:** JVM & Spring Boot (heap, GC, HTTP P99, HikariCP), Docker Containers (CPU, memory, network per container), PostgreSQL (connections, transactions, locks, cache hit ratio)

**Alert rules:** Confirm P99 > 5s, auth success rate < 70%, circuit breaker open > 1min, HikariCP pool > 90%, pending connections > 0, PostgreSQL replication lag > 100MB, cache hit ratio < 90%, JVM heap > 90%, service down > 1min

---

## Project Structure

```
payment-gateway/
  acquirer-core-backend/       # Payment orchestrator (Non-PCI, :8080)
  card-vault-service/          # AES-256-GCM PAN storage (PCI, internal)
  card-auth-service/           # Auth/capture dispatch (PCI, internal)
  token-service/               # Payment method tokens (PCI, internal)
  external-payment-gateway/    # Mock gateway simulator (Non-PCI, :8081)
  ledger-service/              # CDC-driven double-entry ledger (Non-PCI, :8082)
  merchant-service/            # Merchant CRUD (Non-PCI, :8087)
  api_tests/                   # 6 test modules, 27 tests
  infra/
    monitoring/                # Prometheus config, Grafana dashboards, alert rules
    secrets/                   # Docker secrets (encryption keys, webhook secret)
    init-db.sql                # Creates 6 databases
    debezium-connector.json    # CDC connector config
  docs/
    learnings/                 # 55 engineering learnings across 13 topics
    test.md                    # Manual testing guide
    tech_spec_v2.md            # Full technical specification
```

---

## Architecture Evolution

Each version was driven by bugs found in stress testing, not by upfront design:

```
v1  Monolith with in-process mock         Found race conditions via stress test
v2  + Pessimistic locking + idempotency   Fixed double-confirm, double-capture
v3  + External gateway service            Discovered webhook-before-commit bug
v4  + Transactional outbox                Eliminated silent payment loss on crash
v5  + Redis locks + HMAC webhooks         100 TPS sustained, secure webhooks
v6  + Debezium CDC + Kafka + Ledger       Event-driven double-entry accounting
v7  + Monitoring + consistency tests      End-to-end ledger validation
v8  + PCI service split + Resilience4j    Circuit breakers, Flyway, ShedLock, 7 services
```

See [55 engineering learnings](docs/learnings/) for the complete problem-solving narrative.

---

## Documentation

| Document | Description |
|----------|-------------|
| [Engineering Learnings](docs/learnings/) | 55 lessons across 13 topics: concurrency, security, CDC, resilience, performance |
| [Technical Specification](docs/tech_spec_v2.md) | Architecture spec, data models, state machines |
| [Manual Testing Guide](docs/test.md) | curl-based walkthrough of every endpoint |
| [Future Improvements](docs/future_improvements.md) | Planned enhancements |
| [Original Learnings](docs/learnings.md) | Full chronological learning journal |

---

## Built with Claude Code

This project was built entirely using AI-assisted development with [Claude Code](https://claude.ai/code). Every service, test, infrastructure config, and documentation file was created through iterative prompting. The [55 engineering learnings](docs/learnings/) capture the real-time problem-solving process -- including mistakes, debugging sessions, and architectural pivots that shaped the final system.
