# Architecture Evolution

---

## 12. Architecture evolution summary

```
v1: Monolith with in-process mock gateway
    → Found race conditions via stress test

v2: Pessimistic locking + idempotency
    → Fixed double-confirm, double-capture, webhook replay

v3: External gateway-server (separate Spring Boot app)
    → Realistic async webhooks, discovered webhook-before-commit bug

v4: Commit-first dispatch + retry scheduler
    → Eliminated payment loss on crash, but afterCommit blocked Tomcat threads

v5: Async dispatch pool + advisory locks + HMAC webhooks
    → 100+ TPS sustained, correct idempotency, secure webhooks

v6: Debezium CDC + Kafka + Ledger service + Docker Compose
    → Event-driven double-entry ledger, full-stack containerized deployment

v7: Ledger consistency validation + KRaft + observability
    → End-to-end double-entry validation in stress tests, Zookeeper removed,
      idle DB load explained (Hikari pool + schedulers + WAL replication)
```

Each version was driven by a specific failure found in testing, not by upfront design.
The architecture emerged from concrete problems, not abstract principles.

```
v8: PCI service split + Resilience4j + Flyway + ShedLock + structured errors
    → card-vault-service (AES-256-GCM PAN storage), token-service (payment methods),
      card-auth-service (authorization orchestration). Circuit breaker + bulkhead for
      Redis fallback. Flyway migrations replace ddl-auto=update. ShedLock prevents
      duplicate scheduler runs. Input validation, API versioning (/v1/), OpenAPI docs,
      structured JSON logging, dead-letter events for CDC failures.
```
