package com.payment.vault.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "card_data")
class CardData(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "encrypted_pan", nullable = false, columnDefinition = "TEXT")
    val encryptedPan: String,

    @Column(name = "exp_month", nullable = false)
    val expMonth: Int,

    @Column(name = "exp_year", nullable = false)
    val expYear: Int,

    @Column(name = "cardholder_name")
    val cardholderName: String? = null,

    @Column(name = "key_version", nullable = false)
    val keyVersion: Int = 1,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
