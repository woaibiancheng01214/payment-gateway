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
@Table(name = "payment_attempts")
data class PaymentAttempt(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val paymentIntentId: String,

    @Column(nullable = false)
    val paymentMethod: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentAttemptStatus = PaymentAttemptStatus.PENDING,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
