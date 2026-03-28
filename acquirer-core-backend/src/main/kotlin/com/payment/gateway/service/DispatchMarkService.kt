package com.payment.gateway.service

import com.payment.gateway.repository.InternalAttemptRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Separate bean so Spring's proxy can apply REQUIRES_NEW correctly.
 * Marks an InternalAttempt as dispatched in its own short transaction.
 */
@Service
class DispatchMarkService(
    private val internalAttemptRepository: InternalAttemptRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDispatched(internalAttemptId: String) {
        val attempt = internalAttemptRepository.findById(internalAttemptId).orElse(null) ?: return
        attempt.dispatched = true
        attempt.updatedAt = Instant.now()
        internalAttemptRepository.save(attempt)
        log.debug("Marked InternalAttempt $internalAttemptId as dispatched")
    }
}
