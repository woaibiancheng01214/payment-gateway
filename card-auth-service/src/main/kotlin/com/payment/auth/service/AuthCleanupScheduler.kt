package com.payment.auth.service

import com.payment.auth.entity.*
import com.payment.auth.repository.InternalAttemptRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AuthCleanupScheduler(
    private val internalAttemptRepository: InternalAttemptRepository,
    private val authService: AuthService,
    private val gatewayClient: GatewayClient,

    @Value("\${payment.timeout.capture-seconds:60}")
    private val captureTimeoutSeconds: Long,

    @Value("\${payment.dispatch.stale-seconds:10}")
    private val dispatchStaleSeconds: Long,

    @Value("\${payment.cleanup.batch-size:50}")
    private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
                expireCaptureAttemptsBatch(batch)
                expired += batch.size
            } catch (e: Exception) {
                log.error("Failed to expire capture batch: ${e.message}")
            }
        }
        if (expired > 0) log.info("Cleanup: expired $expired capture attempt(s)")
    }

    @Transactional
    fun expireCaptureAttemptsBatch(batch: List<InternalAttempt>) {
        val now = Instant.now()
        for (ia in batch) {
            ia.status = InternalAttemptStatus.EXPIRED
            ia.updatedAt = now
            internalAttemptRepository.save(ia)
        }
    }

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
                authService.markDispatched(ia.id)
                dispatched++
            } catch (e: Exception) {
                log.error("Dispatch-retry failed for ${ia.id}: ${e.message}")
            }
        }
        if (dispatched > 0) log.info("Dispatch-retry: successfully dispatched $dispatched attempt(s)")
    }
}
