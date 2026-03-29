package com.payment.merchant.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class MerchantStatus {
    ACTIVE,
    INACTIVE
}

@Entity
@Table(name = "merchants")
class Merchant(
    @Id
    @Column(nullable = false, updatable = false)
    val id: String = "merch_${UUID.randomUUID()}",

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var status: MerchantStatus = MerchantStatus.ACTIVE,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Merchant) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Merchant(id=$id, name=$name, status=$status)"
}
