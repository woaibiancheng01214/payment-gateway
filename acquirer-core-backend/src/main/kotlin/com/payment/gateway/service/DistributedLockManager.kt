package com.payment.gateway.service

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

enum class LockSource { REDIS, DB_FALLBACK }

data class LockResult(val lockValue: String?, val acquiredVia: LockSource)

@Service
class DistributedLockManager(
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val LOCK_PREFIX = "lock:intent:"
        private val LOCK_TTL = Duration.ofSeconds(30)
    }

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "redisLockCircuitBreaker",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(5)
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
        // Try Redis first via circuit breaker
        try {
            val result = CircuitBreaker.decorateSupplier(circuitBreaker) {
                acquireRedisLock(intentId)
            }.get()

            if (result != null) {
                return LockResult(result, LockSource.REDIS)
            }
            // Lock is held by another request
            throw IllegalStateException("Another operation is already in progress for PaymentIntent $intentId")
        } catch (e: IllegalStateException) {
            throw e // re-throw state conflicts (lock already held)
        } catch (e: Exception) {
            // Redis failed or circuit breaker is open — fall back to DB-only locking
            log.warn("Redis lock unavailable (circuit breaker state: ${circuitBreaker.state}), falling back to DB lock: ${e.message}")
            return acquireWithBulkhead(intentId)
        }
    }

    fun releaseLock(intentId: String, lockResult: LockResult) {
        if (lockResult.acquiredVia == LockSource.REDIS && lockResult.lockValue != null) {
            releaseRedisLock(intentId, lockResult.lockValue)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun acquireWithBulkhead(intentId: String): LockResult {
        try {
            return Bulkhead.decorateSupplier(bulkhead) {
                LockResult(null, LockSource.DB_FALLBACK)
            }.get()
        } catch (e: Exception) {
            throw IllegalStateException("Service temporarily unavailable — too many concurrent DB fallback requests")
        }
    }

    // ── Redis cache helpers for idempotency (create flow) ──────────────────

    fun redisGetIdem(key: String, requestHash: String): String? {
        return try {
            CircuitBreaker.decorateSupplier(circuitBreaker) {
                redisGetIdemInternal(key, requestHash)
            }.get()
        } catch (e: IllegalArgumentException) {
            throw e // hash mismatch — real error, propagate
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
