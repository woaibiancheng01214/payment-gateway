package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "idempotency_keys")
data class IdempotencyKey(
    @Id
    val key: String,

    @Column(nullable = false)
    val requestHash: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    var response: String,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
