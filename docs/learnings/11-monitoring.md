# Monitoring & Observability

---

## 31. Prometheus + Grafana > polling `docker stats` in a script

The stress test already collected CPU, heap, and thread counts via Spring Boot Actuator
during load. But those are point-in-time snapshots printed to stdout — no history, no
visualization, no correlation across services.

Adding Prometheus + Grafana + cAdvisor + postgres-exporter to docker-compose gave:

- **Per-service JVM metrics** (Micrometer → `/actuator/prometheus`): heap used/committed/max,
  GC pause rate, live thread count by state, HTTP request rate and P99 latency, HikariCP
  active/pending/max connections, Tomcat busy threads.
- **Per-container resource metrics** (cAdvisor): CPU %, memory usage and % of limit,
  network RX/TX rate, filesystem I/O — for *every* container including Postgres, Redis, Kafka.
- **PostgreSQL metrics** (postgres-exporter): active connections per database, transaction
  commit/rollback rate, rows fetched/inserted/updated/deleted, locks by mode, cache hit ratio,
  replication lag.

The implementation required just two changes per service:
1. `runtimeOnly("io.micrometer:micrometer-registry-prometheus")` in `build.gradle.kts`
2. Add `prometheus` to `management.endpoints.web.exposure.include` in `application.properties`

Three pre-built Grafana dashboards are auto-provisioned from JSON files via Grafana's
provisioning API — no manual dashboard setup needed after `docker-compose up`.

**Prometheus data persistence:** Without a Docker volume, Prometheus time-series data is
lost on container restart. Adding `prometheus-data:/prometheus` as a named volume makes
metrics survive `docker-compose down/up` (only cleared with `docker-compose down -v`).

**Takeaway:** Real-time dashboards during stress tests reveal patterns that point-in-time
snapshots miss — like a gradual connection pool fill-up or a CPU spike that correlates with
GC pauses. The setup cost (4 containers + 1 dependency per service) pays for itself on
the first load test.

---

## 44. Correlation IDs are the foundation of distributed observability

With 6 synchronous HTTP hops per confirm (checkout → vault → token → auth → vault → token
→ gateway), a single payment traverses multiple log streams. Without a shared identifier,
correlating a failed payment across services requires manually matching timestamps — which
is unreliable at >10 TPS because multiple requests overlap.

**Fix:** Added `CorrelationIdFilter` (a `OncePerRequestFilter`) to all 7 services:
1. Extracts `X-Correlation-Id` from the incoming request (or generates a UUID)
2. Stores it in SLF4J MDC as `correlationId`
3. Echoes it in the response header
4. Cleans up MDC after the request completes

The `HttpClientConfig` bean attaches a `ClientHttpRequestInterceptor` to all RestTemplate
instances that reads the correlation ID from MDC and adds it as a header on outbound calls.

Since all services use logstash-logback-encoder for JSON logging, the MDC field is
automatically included in every log line. Searching Kibana/Loki for
`correlationId: "abc-123"` returns the complete distributed trace across all services.

**Takeaway:** Add correlation IDs before you need them. Once you're debugging a production
issue at 100+ TPS, it's too late to wish you had them. The implementation is ~30 lines of
code per service and one interceptor.

---

## 52. Business metrics are the primary SLIs — JVM metrics are secondary

The monitoring stack (Prometheus + Grafana) had excellent infrastructure metrics (JVM heap,
GC pauses, HikariCP pools, container CPU) but zero payment-specific metrics. You couldn't
answer: "What's our current authorization success rate?" or "What's the confirm P99?"

**Fix:** Added Micrometer counters and timers to `PaymentIntentService`:

| Metric | Type | Tags |
|--------|------|------|
| `payment.intents.created` | Counter | — |
| `payment.intents.confirm.duration` | Timer | P50, P95, P99 |
| `payment.intents.capture.requested` | Counter | — |
| `payment.webhooks.processed` | Counter | — |
| `payment.intents.status_changes` | Counter | from, to |

The confirm timer uses `Timer.start()` / `sample.stop(timer)` pattern to measure
wall-clock time including lock acquisition, PCI service calls, and transaction commit.

**Takeaway:** Infrastructure metrics tell you *how* the system is performing. Business
metrics tell you *what* it's accomplishing. Both are necessary, but if you had to choose
one, business metrics are more actionable — "auth success rate dropped 20%" is a clearer
signal than "heap usage increased 10%".

---

## 53. Prometheus alerting rules — detect problems before users do

Prometheus was scraping all services every 5s but had no alert rules. Problems were only
discovered by running stress tests or reading logs.

**Fix:** Created `infra/monitoring/alerts.yml` with rules for:
- Confirm P99 latency > 5s (critical)
- Auth success rate < 70% (warning)
- Circuit breaker open > 1 minute (critical)
- HikariCP pool > 90% active (warning)
- HikariCP pending connections > 0 (critical)
- PostgreSQL replication lag > 100MB (warning)
- PostgreSQL cache hit ratio < 90% (warning)
- JVM heap > 90% (warning)
- Service down > 1 minute (critical)

Wired into `prometheus.yml` via `rule_files` and mounted in docker-compose.

**Takeaway:** Alerting rules encode operational knowledge. Each rule is a lesson from a
past incident or stress test. Without alerts, the same problem has to be rediscovered
by a human each time it occurs.
