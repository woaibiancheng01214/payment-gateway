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
