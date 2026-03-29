package com.payment.ledger.service

import com.payment.ledger.repository.DeadLetterEventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class DeadLetterRetryScheduler(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val paymentEventConsumer: PaymentEventConsumer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_RETRIES = 5
        // Backoff intervals in seconds: 1min, 5min, 30min, 2h, 12h
        private val BACKOFF_SECONDS = longArrayOf(60, 300, 1800, 7200, 43200)
    }

    @Scheduled(fixedDelay = 60_000) // check every minute
    fun retryDeadLetterEvents() {
        val retryable = deadLetterEventRepository.findRetryable(
            now = Instant.now(),
            maxRetries = MAX_RETRIES,
            pageable = Pageable.ofSize(5) // limit per sweep to avoid thundering herd
        )
        if (retryable.isEmpty()) return

        log.info("Dead letter retry: found ${retryable.size} event(s) to retry")

        for (event in retryable) {
            try {
                paymentEventConsumer.consume(event.payload)
                event.resolvedAt = Instant.now()
                deadLetterEventRepository.save(event)
                log.info("Dead letter event ${event.id} retried successfully (attempt ${event.retryCount + 1})")
            } catch (e: Exception) {
                event.retryCount++
                if (event.retryCount >= MAX_RETRIES) {
                    event.nextRetryAt = null // stop retrying
                    log.error("Dead letter event ${event.id} exhausted retries ($MAX_RETRIES): ${e.message}")
                } else {
                    val backoffSeconds = BACKOFF_SECONDS.getOrElse(event.retryCount) { BACKOFF_SECONDS.last() }
                    event.nextRetryAt = Instant.now().plusSeconds(backoffSeconds)
                    log.warn("Dead letter event ${event.id} retry ${event.retryCount} failed, next retry in ${backoffSeconds}s: ${e.message}")
                }
                deadLetterEventRepository.save(event)
            }
        }
    }
}
