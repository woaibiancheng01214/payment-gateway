package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    /** Auth webhook never arrived within the configured timeout window. Terminal — cannot recover. */
    EXPIRED
}

@Entity
@Table(
    name = "payment_intents",
    indexes = [
        Index(name = "idx_pi_status_updated", columnList = "status, updatedAt")
    ]
)
data class PaymentIntent(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentIntentStatus = PaymentIntentStatus.REQUIRES_CONFIRMATION,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
