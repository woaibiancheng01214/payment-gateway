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
    EXPIRED
}

@Entity
@Table(
    name = "payment_intents",
    indexes = [
        Index(name = "idx_pi_status_updated", columnList = "status, updatedAt"),
        Index(name = "idx_pi_merchant_id", columnList = "merchant_id")
    ]
)
class PaymentIntent(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "merchant_id", nullable = false)
    val merchantId: String,

    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val currency: Currency,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(length = 22)
    val statementDescriptor: String? = null,

    @Column(columnDefinition = "TEXT")
    val metadata: String? = null,

    val customerEmail: String? = null,

    val customerId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentIntentStatus = PaymentIntentStatus.REQUIRES_CONFIRMATION,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentIntent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "PaymentIntent(id=$id, merchantId=$merchantId, status=$status, amount=$amount, currency=$currency)"
}
