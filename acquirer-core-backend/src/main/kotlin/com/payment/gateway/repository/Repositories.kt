package com.payment.gateway.repository

import com.payment.gateway.entity.*
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
interface PaymentIntentRepository : JpaRepository<PaymentIntent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT p FROM PaymentIntent p WHERE p.id = :id")
    fun findByIdForUpdate(id: String): Optional<PaymentIntent>

    @Query("SELECT p FROM PaymentIntent p WHERE p.status = :status AND p.updatedAt < :before ORDER BY p.updatedAt ASC LIMIT :limit")
    fun findByStatusAndUpdatedAtBefore(status: PaymentIntentStatus, before: Instant, limit: Int): List<PaymentIntent>

    fun findByMerchantId(merchantId: String, pageable: Pageable): Page<PaymentIntent>

    // Cursor-based pagination: fetch intents created before the cursor, ordered newest first
    @Query("""
        SELECT p FROM PaymentIntent p
        WHERE p.createdAt < :cursorTime OR (p.createdAt = :cursorTime AND p.id < :cursorId)
        ORDER BY p.createdAt DESC, p.id DESC
    """)
    fun findWithCursor(cursorTime: Instant, cursorId: String, pageable: Pageable): List<PaymentIntent>

    // Cursor-based pagination scoped to a merchant
    @Query("""
        SELECT p FROM PaymentIntent p
        WHERE p.merchantId = :merchantId
          AND (p.createdAt < :cursorTime OR (p.createdAt = :cursorTime AND p.id < :cursorId))
        ORDER BY p.createdAt DESC, p.id DESC
    """)
    fun findByMerchantIdWithCursor(merchantId: String, cursorTime: Instant, cursorId: String, pageable: Pageable): List<PaymentIntent>

    // Initial page (no cursor)
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): List<PaymentIntent>

    @Query("SELECT p FROM PaymentIntent p WHERE p.merchantId = :merchantId ORDER BY p.createdAt DESC, p.id DESC")
    fun findByMerchantIdOrdered(merchantId: String, pageable: Pageable): List<PaymentIntent>
}

@Repository
interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, String> {
    fun findByPaymentIntentId(paymentIntentId: String): List<PaymentAttempt>
    fun findTopByPaymentIntentIdOrderByCreatedAtDesc(paymentIntentId: String): PaymentAttempt?
}

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, String> {
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :before")
    fun deleteExpiredBefore(before: Instant): Int
}
