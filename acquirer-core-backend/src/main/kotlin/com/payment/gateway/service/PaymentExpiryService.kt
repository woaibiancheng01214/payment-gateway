package com.payment.gateway.service

import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Handles per-record expiry in its own transactions.
 * Lives in a separate bean from PaymentCleanupScheduler so that
 * Spring's proxy can apply @Transactional(REQUIRES_NEW) on each
 * call, preventing a single failure from rolling back the whole sweep.
 */
@Service
class PaymentExpiryService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val internalAttemptRepository: InternalAttemptRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Expires one auth-hung intent. Returns true if it was actually expired,
     * false if a concurrent webhook already resolved it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expireAuthIntent(intentId: String): Boolean {
        val intent = paymentIntentRepository.findByIdForUpdate(intentId).orElse(null) ?: return false

        // Re-check under lock — webhook may have resolved it while we waited for the lock
        if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) return false

        val attempts = paymentAttemptRepository.findByPaymentIntentId(intentId)
        log.warn("Expiring auth-hung intent $intentId (stuck since ${intent.updatedAt})")
        val now = Instant.now()

        for (attempt in attempts.filter { it.status == PaymentAttemptStatus.PENDING }) {
            for (ia in internalAttemptRepository.findByPaymentAttemptId(attempt.id)
                .filter { it.status in setOf(InternalAttemptStatus.PENDING, InternalAttemptStatus.TIMEOUT) }) {
                ia.status = InternalAttemptStatus.EXPIRED
                ia.updatedAt = now
                internalAttemptRepository.save(ia)
            }
            attempt.status = PaymentAttemptStatus.EXPIRED
            attempt.updatedAt = now
            paymentAttemptRepository.save(attempt)
        }

        intent.status = PaymentIntentStatus.EXPIRED
        intent.updatedAt = now
        paymentIntentRepository.save(intent)
        return true
    }
}
