package com.payment.token.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_methods")
class PaymentMethod(

    @Id
    @Column(nullable = false, updatable = false)
    val id: String = "pm_${UUID.randomUUID()}",

    @Column(name = "customer_id")
    var customerId: String? = null,

    @Column(name = "card_data_id", nullable = false)
    val cardDataId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    val brand: CardBrand,

    @Column(nullable = false, length = 4)
    val last4: String,

    @Column(name = "exp_month", nullable = false)
    val expMonth: Int,

    @Column(name = "exp_year", nullable = false)
    val expYear: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var status: PaymentMethodStatus = PaymentMethodStatus.ACTIVE,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentMethod) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
