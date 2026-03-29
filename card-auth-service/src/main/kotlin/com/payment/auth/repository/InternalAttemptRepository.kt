package com.payment.auth.repository

import com.payment.auth.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface InternalAttemptRepository : JpaRepository<InternalAttempt, String> {
    fun findByPaymentAttemptId(paymentAttemptId: String): List<InternalAttempt>
    fun findByPaymentAttemptIdIn(paymentAttemptIds: List<String>): List<InternalAttempt>

    @Query("""
        SELECT ia FROM InternalAttempt ia
        WHERE ia.type = :type
          AND ia.status IN ('PENDING', 'TIMEOUT')
          AND ia.createdAt < :before
    """)
    fun findStaleByTypeAndCreatedAtBefore(type: InternalAttemptType, before: Instant): List<InternalAttempt>

    @Query("""
        SELECT ia FROM InternalAttempt ia
        WHERE ia.dispatched = false
          AND ia.status = 'PENDING'
          AND ia.createdAt < :before
        ORDER BY ia.createdAt ASC
        LIMIT :limit
    """)
    fun findUndispatched(before: Instant, limit: Int = 50): List<InternalAttempt>
}
