package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class PaymentAttemptStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
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
class PaymentAttempt(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val paymentIntentId: String,

    @Column(name = "payment_method", nullable = false)
    val paymentMethodId: String,

    @Enumerated(EnumType.STRING)
    val cardBrand: CardBrand? = null,

    val last4: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentAttemptStatus = PaymentAttemptStatus.PENDING,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentAttempt) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "PaymentAttempt(id=$id, status=$status, paymentIntentId=$paymentIntentId)"
}
