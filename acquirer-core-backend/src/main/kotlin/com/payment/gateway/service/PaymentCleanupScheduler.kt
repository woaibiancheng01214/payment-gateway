package com.payment.gateway.service

import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class PaymentCleanupScheduler(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val internalAttemptRepository: InternalAttemptRepository,
    private val paymentExpiryService: PaymentExpiryService,
    private val gatewayClient: GatewayClient,
    private val dispatchMarkService: DispatchMarkService,

    @Value("\${payment.timeout.auth-seconds:30}")
    private val authTimeoutSeconds: Long,

    @Value("\${payment.timeout.capture-seconds:60}")
    private val captureTimeoutSeconds: Long,

    @Value("\${payment.cleanup.batch-size:50}")
    private val batchSize: Int,

    @Value("\${payment.dispatch.stale-seconds:10}")
    private val dispatchStaleSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Finds REQUIRES_CONFIRMATION intents older than auth-timeout and expires them
     * one-by-one in individual transactions so a single failure won't roll back
     * the whole batch.
     */
    @Scheduled(fixedDelayString = "\${payment.cleanup.interval-seconds:10}000")
    fun sweepExpiredAuthAttempts() {
        val authDeadline = Instant.now().minusSeconds(authTimeoutSeconds)
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

    /**
     * Finds CAPTURE InternalAttempts older than capture-timeout and marks them EXPIRED
     * in batches of 10 to minimize transaction size and lock contention.
     * The PaymentIntent stays AUTHORIZED so the merchant can retry capture.
     */
    @Scheduled(fixedDelayString = "\${payment.cleanup.interval-seconds:10}000")
    fun sweepExpiredCaptureAttempts() {
        val captureDeadline = Instant.now().minusSeconds(captureTimeoutSeconds)
        val staleCaptures = internalAttemptRepository.findStaleByTypeAndCreatedAtBefore(
            InternalAttemptType.CAPTURE, captureDeadline
        )
        if (staleCaptures.isEmpty()) return

        log.info("Cleanup: found ${staleCaptures.size} stale CAPTURE attempt(s)")
        var expired = 0
        for (batch in staleCaptures.chunked(10)) {
            try {
                paymentExpiryService.expireCaptureAttemptsBatch(batch)
                expired += batch.size
            } catch (e: Exception) {
                log.error("Failed to expire capture batch: ${e.message}")
            }
        }
        if (expired > 0) log.info("Cleanup: expired $expired capture attempt(s)")
    }

    /**
     * Finds InternalAttempts that were committed to the DB but never dispatched to the
     * gateway (e.g., JVM crashed after commit, or network error on the afterCommit call).
     * Re-dispatches them and marks them as dispatched.
     */
    @Scheduled(fixedDelayString = "\${payment.dispatch.retry-interval-seconds:3}000")
    fun sweepUndispatchedAttempts() {
        val staleThreshold = Instant.now().minusSeconds(dispatchStaleSeconds)
        val undispatched = internalAttemptRepository.findUndispatched(staleThreshold, batchSize)
        if (undispatched.isEmpty()) return

        log.info("Dispatch-retry: found ${undispatched.size} undispatched attempt(s)")
        var dispatched = 0
        for (ia in undispatched) {
            try {
                gatewayClient.dispatch(ia)
                dispatchMarkService.markDispatched(ia.id)
                dispatched++
            } catch (e: Exception) {
                log.error("Dispatch-retry failed for ${ia.id}: ${e.message}")
            }
        }
        if (dispatched > 0) log.info("Dispatch-retry: successfully dispatched $dispatched attempt(s)")
    }
}
