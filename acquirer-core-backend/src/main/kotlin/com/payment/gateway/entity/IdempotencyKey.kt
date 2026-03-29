package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "idempotency_keys")
class IdempotencyKey(
    @Id
    val key: String,

    @Column(nullable = false)
    val requestHash: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    var response: String,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    val expiresAt: Instant = Instant.now().plusSeconds(48 * 3600)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdempotencyKey) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "IdempotencyKey(key=$key)"
}
