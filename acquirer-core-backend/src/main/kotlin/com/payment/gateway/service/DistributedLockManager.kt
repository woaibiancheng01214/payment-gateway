package com.payment.gateway.service

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class LockAlreadyHeldException(message: String) : RuntimeException(message)

enum class LockSource { REDIS, DB_FALLBACK }

class LockResult(
    val lockValue: String?,
    val acquiredVia: LockSource,
    internal val advisoryConnection: Connection? = null
)

@Service
class DistributedLockManager(
    private val redisTemplate: StringRedisTemplate,
    private val dataSource: DataSource
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val LOCK_PREFIX = "lock:intent:"
        private val LOCK_TTL = Duration.ofSeconds(30)
    }

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "redisLockCircuitBreaker",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .failureRateThreshold(50f)
            .slowCallRateThreshold(80f)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(10)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build()
    )

    private val bulkhead: Bulkhead = Bulkhead.of(
        "dbLockFallbackBulkhead",
        BulkheadConfig.custom()
            .maxConcurrentCalls(5)
            .maxWaitDuration(Duration.ZERO)
            .build()
    )

    fun acquireLock(intentId: String): LockResult {
        try {
            val result = CircuitBreaker.decorateSupplier(circuitBreaker) {
                acquireRedisLock(intentId)
            }.get()

            if (result != null) {
                return LockResult(result, LockSource.REDIS)
            }
            throw LockAlreadyHeldException("Another operation is already in progress for PaymentIntent $intentId")
        } catch (e: LockAlreadyHeldException) {
            throw e
        } catch (e: Exception) {
            log.warn("Redis lock unavailable (circuit breaker state: ${circuitBreaker.state}), falling back to DB advisory lock: ${e.message}")
            return acquireWithBulkhead(intentId)
        }
    }

    fun releaseLock(intentId: String, lockResult: LockResult) {
        when (lockResult.acquiredVia) {
            LockSource.REDIS -> {
                if (lockResult.lockValue != null) releaseRedisLock(intentId, lockResult.lockValue)
            }
            LockSource.DB_FALLBACK -> {
                try {
                    lockResult.advisoryConnection?.close()
                } catch (e: Exception) {
                    log.warn("Advisory lock connection close failed for intent $intentId: ${e.message}")
                }
            }
        }
    }

    private fun acquireWithBulkhead(intentId: String): LockResult {
        try {
            return Bulkhead.decorateSupplier(bulkhead) {
                acquireAdvisoryLock(intentId)
            }.get()
        } catch (e: LockAlreadyHeldException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Service temporarily unavailable — too many concurrent DB fallback requests")
        }
    }

    private fun acquireAdvisoryLock(intentId: String): LockResult {
        val conn = dataSource.connection
        var success = false
        try {
            val acquired = conn.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))").use { stmt ->
                stmt.setString(1, LOCK_PREFIX + intentId)
                stmt.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
            if (!acquired) {
                throw LockAlreadyHeldException("Another operation is already in progress for PaymentIntent $intentId")
            }
            success = true
            return LockResult(null, LockSource.DB_FALLBACK, advisoryConnection = conn)
        } finally {
            if (!success) conn.close()
        }
    }

    // ── Redis cache helpers for idempotency (create flow) ──────────────────

    fun redisGetIdem(key: String, requestHash: String): String? {
        return try {
            CircuitBreaker.decorateSupplier(circuitBreaker) {
                redisGetIdemInternal(key, requestHash)
            }.get()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            log.warn("Redis idem GET failed (circuit breaker), falling through to DB: ${e.message}")
            null
        }
    }

    fun redisSetIdem(key: String, requestHash: String, responseJson: String) {
        try {
            CircuitBreaker.decorateRunnable(circuitBreaker) {
                redisTemplate.opsForValue().set("idempotency:$key", "$requestHash\n$responseJson", Duration.ofHours(24))
            }.run()
        } catch (e: Exception) {
            log.warn("Redis idem SET failed for key '$key': ${e.message}")
        }
    }

    private fun redisGetIdemInternal(key: String, requestHash: String): String? {
        val raw = redisTemplate.opsForValue().get("idempotency:$key") ?: return null
        val sep = raw.indexOf('\n')
        if (sep < 0) return raw
        val cachedHash = raw.substring(0, sep)
        val cachedResponse = raw.substring(sep + 1)
        if (cachedHash != requestHash) {
            throw IllegalArgumentException("Idempotency key was already used with a different amount/currency")
        }
        return cachedResponse
    }

    private fun acquireRedisLock(intentId: String): String? {
        val lockValue = UUID.randomUUID().toString()
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(LOCK_PREFIX + intentId, lockValue, LOCK_TTL)
            ?: false
        return if (acquired) lockValue else null
    }

    private fun releaseRedisLock(intentId: String, lockValue: String) {
        try {
            val script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
            redisTemplate.execute(
                org.springframework.data.redis.core.script.DefaultRedisScript(script, Long::class.java),
                listOf(LOCK_PREFIX + intentId),
                lockValue
            )
        } catch (e: Exception) {
            log.warn("Redis lock release failed for intent $intentId: ${e.message}")
        }
    }
}
