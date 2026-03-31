package com.payment.gateway.service

import com.payment.gateway.repository.PaymentAttemptRepository
import com.payment.gateway.repository.PaymentIntentRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Component
class ConfirmDispatchScheduler(
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val paymentIntentRepository: PaymentIntentRepository,
    private val authClient: AuthClient,
    private val transactionTemplate: TransactionTemplate,

    @Value("\${payment.dispatch.stale-seconds:10}")
    private val dispatchStaleSeconds: Long,

    @Value("\${payment.dispatch.batch-size:50}")
    private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${payment.dispatch.retry-interval-seconds:3}000")
    @SchedulerLock(name = "sweepUndispatchedConfirms", lockAtMostFor = "9s", lockAtLeastFor = "2s")
    fun sweepUndispatchedConfirms() {
        val staleThreshold = Instant.now().minusSeconds(dispatchStaleSeconds)
        val undispatched = paymentAttemptRepository.findUndispatched(staleThreshold, batchSize)
        if (undispatched.isEmpty()) return

        log.info("Confirm dispatch-retry: found ${undispatched.size} undispatched attempt(s)")
        var dispatched = 0
        for (attempt in undispatched) {
            try {
                val intent = paymentIntentRepository.findById(attempt.paymentIntentId).orElse(null)
                    ?: continue
                authClient.confirm(
                    intent.id, attempt.paymentMethodId, attempt.id,
                    intent.amount, intent.currency.name
                )
                transactionTemplate.execute {
                    paymentAttemptRepository.markDispatched(attempt.id, Instant.now())
                }
                dispatched++
            } catch (e: Exception) {
                log.error("Confirm dispatch-retry failed for ${attempt.id}: ${e.message}")
            }
        }
        if (dispatched > 0) log.info("Confirm dispatch-retry: dispatched $dispatched attempt(s)")
    }
}
