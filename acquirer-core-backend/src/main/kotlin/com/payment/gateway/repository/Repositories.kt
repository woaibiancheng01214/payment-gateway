package com.payment.gateway.repository

import com.payment.gateway.entity.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
interface PaymentIntentRepository : JpaRepository<PaymentIntent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.id = :id")
    fun findByIdForUpdate(id: String): Optional<PaymentIntent>

    @Query("SELECT p FROM PaymentIntent p WHERE p.status = :status AND p.updatedAt < :before ORDER BY p.updatedAt ASC LIMIT :limit")
    fun findByStatusAndUpdatedAtBefore(status: PaymentIntentStatus, before: Instant, limit: Int): List<PaymentIntent>
}

@Repository
interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, String> {
    fun findByPaymentIntentId(paymentIntentId: String): List<PaymentAttempt>
    fun findTopByPaymentIntentIdOrderByCreatedAtDesc(paymentIntentId: String): PaymentAttempt?
}

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

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, String>
