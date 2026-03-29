package com.payment.gateway.service

import com.payment.gateway.repository.IdempotencyKeyRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class IdempotencyCleanupScheduler(
    private val idempotencyKeyRepository: IdempotencyKeyRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3600_000) // every hour
    @SchedulerLock(name = "cleanupExpiredIdempotencyKeys", lockAtMostFor = "55m", lockAtLeastFor = "5m")
    @Transactional
    fun cleanupExpiredKeys() {
        val deleted = idempotencyKeyRepository.deleteExpiredBefore(Instant.now())
        if (deleted > 0) {
            log.info("Idempotency cleanup: deleted $deleted expired key(s)")
        }
    }
}
