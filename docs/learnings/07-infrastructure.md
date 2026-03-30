# Infrastructure & Database

## 11. Docker Compose as the integration test environment

The full stack (PostgreSQL with WAL logical replication, Kafka, Debezium, acquirer-core,
external-gateway, ledger-service, and a one-shot connector registration) runs in a single
`docker-compose up`. This proved essential for:

- Testing CDC end-to-end (Debezium needs WAL access)
- Verifying service-to-service communication (webhook callbacks use Docker network names)
- Reproducing production-like failure modes (kill a container, watch recovery)

Infrastructure files needed:
- `infra/init-db.sql` — creates both databases, enables replication
- `infra/debezium-connector.json` — Debezium connector config for the `payment_intents` table
- `Dockerfile` per service — multi-stage build with Gradle

---

## 15. KRaft eliminates Zookeeper — fewer moving parts

The original `docker-compose.yml` used Zookeeper for Kafka coordination. Switching to
**KRaft** (Kafka Raft, available since Kafka 3.3, production-ready since 3.5) removed
an entire container:

- **Before:** Zookeeper container + Kafka container (Zookeeper healthcheck was flaky —
  `nc` not available on some images, `ruok` protocol unreliable in Docker)
- **After:** Single Kafka container with `KAFKA_PROCESS_ROLES=broker,controller` and
  `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093`

Benefits: faster startup (no Zookeeper election), one fewer failure mode, simpler
healthcheck (just `kafka-broker-api-versions`), smaller compose file.

**Takeaway:** If you're on Kafka 3.5+, there's no reason to keep Zookeeper. KRaft is
simpler in every dimension.

---

## 16. Idle database load is normal — understand what's polling

After the stress test finishes and all intents are terminal, pgAdmin still shows:
- ~50 sessions (mostly idle)
- ~6-8 TPS
- ~20k tuples out
- ~600-800 blocks IO

This is not a bug. Three sources explain 100% of the idle load:

**HikariCP connection pool.** Configured with `maximum-pool-size=50`, `minimum-idle=10`.
After a load spike, all 50 connections stay open in `idle` state (`ClientRead` wait event)
until Hikari's `idle-timeout` (default 10 minutes) shrinks the pool back to 10. These
show as "sessions" in pgAdmin but execute zero queries.

**Background schedulers polling empty tables.** Three schedulers run continuously:

| Scheduler | Interval | Query |
|---|---|---|
| `sweepExpiredAuthAttempts` | 10s | `SELECT ... WHERE status = REQUIRES_CONFIRMATION AND updatedAt < deadline` |
| `sweepExpiredCaptureAttempts` | 10s | `SELECT ... WHERE type = CAPTURE AND status IN (PENDING, TIMEOUT)` |
| `sweepUndispatchedAttempts` | **3s** | `SELECT ... WHERE dispatched = false AND status = PENDING` |

The 3-second dispatch-retry sweep is the chattiest — ~20 queries/minute returning empty
result sets. Each still touches index pages, producing the tuples-out and block IO.

**Debezium WAL replication.** The replication slot (`payment_gateway_slot`) streams WAL
changes continuously. Even at rest, the heartbeat (every 10s) keeps the connection active
and shows as an `active` session with `WalSenderWaitForWAL`.

**Takeaway:** "High sessions" after a test usually means idle connection pools, not runaway
queries. Check `pg_stat_activity` and look at the `state` column — `idle` connections are
harmless. The real cost is the scheduler polling, which can be tuned by increasing intervals
when no work is expected.

---

## 25. Flyway migrations — why `ddl-auto=update` is a ticking time bomb

Learning 20 described using `@Column(name)` to work around `ddl-auto=update`'s limitations.
The real fix was switching to **Flyway**:

- `ddl-auto=validate` (not `update`) — Hibernate checks schema alignment at startup without
  modifying anything. If a migration is missing, the app fails fast instead of silently
  creating a wonky schema.
- Versioned SQL files (`V1__initial_schema.sql`, `V2__shedlock_table.sql`) provide an
  auditable history of every schema change.
- Each service has its own migration path because each service has its own database.

**Gotcha with Spring Boot 3.2 + Flyway:** Spring Boot 3.2 ships with Flyway 9.x, which
bundles PostgreSQL support in `flyway-core`. The separate `flyway-database-postgresql`
artifact is only needed for Flyway 10+ (Spring Boot 3.3+). Adding it without a version
causes a "could not resolve" build failure.

**Takeaway:** Schema management is infrastructure, not application code. Let the migration
tool own the DDL, and let Hibernate validate that the code matches.

---

## 39. PostgreSQL defaults are insufficient for microservice connection pools

Running 6 databases on one PostgreSQL instance with default `max_connections=100` and
`shared_buffers=128MB` hits two walls at scale:

1. **Connection limit:** HikariCP pools across 6 services sum to 80+30+20+20+15+10=175
   minimum connections. Default `max_connections=100` means services fail to connect under
   load, with `FATAL: too many connections for role "postgres"`.

2. **Buffer cache thrashing:** 128MB shared_buffers across 6 databases means each database
   gets ~21MB of cache. The `idx_pi_status_updated` index alone exceeds this at scale,
   causing constant disk I/O for scheduler sweep queries.

**Fix:** Explicit tuning in docker-compose command:
```
shared_buffers=256MB      # 25% of container RAM
max_connections=200       # headroom above pool sum
work_mem=4MB              # per-sort memory
checkpoint_completion_target=0.9  # spread checkpoint I/O
effective_cache_size=768MB  # tell planner about OS cache
```

Also increased container memory from 1GB to 2GB — `shared_buffers=256MB` plus 6 databases
of working state plus WAL buffers easily exceeds 1GB.

**Takeaway:** Size PostgreSQL for the sum of all client pools, not for one service.
`max_connections` should be 20-30% above the total pool size to handle transient spikes
and monitoring connections.

---

## 40. HikariCP defaults (10 max) silently bottleneck PCI services

All four new services (`card-vault-service`, `token-service`, `card-auth-service`,
`merchant-service`) were deployed with Spring Boot's default HikariCP configuration:
`maximum-pool-size=10`. The acquirer-core-backend was tuned to 80 connections (Learning 33),
but the downstream services were forgotten.

At 100 TPS, each confirm triggers sequential calls to vault → token → auth. Each of these
services receives ~100 req/s. With 10 connections and ~10-50ms per DB operation, the pool
can theoretically handle ~200-1000 req/s — but add pessimistic locks, scheduler queries,
and connection acquisition overhead, and the effective capacity drops to ~100 req/s.

At 300 TPS peak, these services become the bottleneck, causing `ConnectionTimeoutException`
that cascades through circuit breakers back to the client.

**Fix:** Sized each pool proportionally to expected load:
- card-auth-service: 30 max / 10 min-idle (handles auth dispatch + scheduler sweeps)
- card-vault-service: 20 max / 5 min-idle
- token-service: 20 max / 5 min-idle
- merchant-service: 15 max / 5 min-idle

**Takeaway:** When adding new services, always configure HikariCP explicitly. The default
of 10 is designed for low-traffic apps, not services receiving forwarded load from a
gateway processing 100+ TPS.

---

## 41. RestTemplate creates a new TCP connection per request by default

Spring's `RestTemplate` with the default `SimpleClientHttpRequestFactory` opens a new TCP
connection for every HTTP request and closes it after the response. At 100 TPS with 6
inter-service hops per confirm, that is 600 TCP connections/second being created and
destroyed. At 300 TPS peak: 1800/second.

Each TCP connection costs ~1-3ms for the handshake (even on localhost), contributes to
ephemeral port exhaustion (`TIME_WAIT` socket buildup), and bypasses kernel-level
connection reuse.

**Fix:** Added Apache HttpClient 5 with `PoolingHttpClientConnectionManager` as a
`@Bean`-configured `RestTemplateBuilder`:

```kotlin
val connectionManager = PoolingHttpClientConnectionManager().apply {
    maxTotal = 100
    defaultMaxPerRoute = 50
}
```

This reuses TCP connections across requests, eliminating handshake overhead. The same
`RestTemplateBuilder` bean also attaches a `ClientHttpRequestInterceptor` that forwards
correlation IDs (see Learning 44).

**Takeaway:** Any service making >10 HTTP calls/second to another service needs connection
pooling. Spring Boot does not configure this by default — you must add the HttpClient
dependency and wire up a `PoolingHttpClientConnectionManager`.
