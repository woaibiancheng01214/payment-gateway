package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class PaymentAttemptStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    /** No gateway response arrived within the configured timeout window. */
    EXPIRED
}

@Entity
@Table(
    name = "payment_attempts",
    indexes = [
        Index(name = "idx_pa_intent_id", columnList = "paymentIntentId"),
        Index(name = "idx_pa_intent_id_created", columnList = "paymentIntentId, createdAt")
    ]
)
data class PaymentAttempt(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val paymentIntentId: String,

    @Column(name = "payment_method", nullable = false)
    val paymentToken: String,

    val cardBrand: String? = null,

    val last4: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentAttemptStatus = PaymentAttemptStatus.PENDING,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
