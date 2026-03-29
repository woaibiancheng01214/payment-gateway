package com.payment.gateway.service

import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PaymentExpiryService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val authClient: AuthClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expireAuthIntent(intentId: String): Boolean {
        val intent = paymentIntentRepository.findByIdForUpdate(intentId).orElse(null) ?: return false

        if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) return false

        val attempts = paymentAttemptRepository.findByPaymentIntentId(intentId)
        log.warn("Expiring auth-hung intent $intentId (stuck since ${intent.updatedAt})")
        val now = Instant.now()

        // Expire InternalAttempts via auth-service
        val attemptIds = attempts.filter { it.status == PaymentAttemptStatus.PENDING }.map { it.id }
        authClient.expireAttempts(attemptIds)

        for (attempt in attempts.filter { it.status == PaymentAttemptStatus.PENDING }) {
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
