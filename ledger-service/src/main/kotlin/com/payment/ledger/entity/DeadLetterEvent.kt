package com.payment.ledger.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "dead_letter_events")
class DeadLetterEvent(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val topic: String,

    @Column(nullable = false)
    val partitionNum: Int = 0,

    @Column(nullable = false)
    val offsetNum: Long = 0,

    @Column(columnDefinition = "TEXT")
    val key: String? = null,

    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val errorMessage: String,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    var resolvedAt: Instant? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeadLetterEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DeadLetterEvent(id=$id, topic=$topic, errorMessage=$errorMessage)"
}
