package com.payment.gateway.service

import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PaymentCleanupScheduler(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentExpiryService: PaymentExpiryService,

    @Value("\${payment.timeout.auth-seconds:30}")
    private val authTimeoutSeconds: Long,

    @Value("\${payment.cleanup.batch-size:50}")
    private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Finds REQUIRES_CONFIRMATION intents older than auth-timeout and expires them.
     * Capture expiry and dispatch-retry are handled by card-auth-service.
     */
    @Scheduled(fixedDelayString = "\${payment.cleanup.interval-seconds:10}000")
    @SchedulerLock(name = "sweepExpiredAuthAttempts", lockAtMostFor = "9s", lockAtLeastFor = "5s")
    fun sweepExpiredAuthAttempts() {
        val authDeadline = java.time.Instant.now().minusSeconds(authTimeoutSeconds)
        val staleIntents = paymentIntentRepository.findByStatusAndUpdatedAtBefore(
            PaymentIntentStatus.REQUIRES_CONFIRMATION, authDeadline, batchSize
        )
        if (staleIntents.isEmpty()) return

        log.info("Cleanup: found ${staleIntents.size} stale auth intent(s) (batch=$batchSize)")
        var expired = 0
        for (intent in staleIntents) {
            try {
                if (paymentExpiryService.expireAuthIntent(intent.id)) expired++
            } catch (e: Exception) {
                log.error("Failed to expire intent ${intent.id}: ${e.message}")
            }
        }
        if (expired > 0) log.info("Cleanup: expired $expired auth intent(s)")
    }
}
