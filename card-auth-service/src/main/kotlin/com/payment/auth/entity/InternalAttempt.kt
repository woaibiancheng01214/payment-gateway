package com.payment.auth.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "internal_attempts",
    indexes = [
        Index(name = "idx_ia_attempt_id", columnList = "payment_attempt_id"),
        Index(name = "idx_ia_dispatched_status_created", columnList = "dispatched, status, created_at"),
        Index(name = "idx_ia_type_status_created", columnList = "type, status, created_at")
    ]
)
class InternalAttempt(

    @Id
    @Column(nullable = false)
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "payment_attempt_id", nullable = false)
    val paymentAttemptId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: Provider = Provider.MOCK_VISA,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InternalAttemptStatus = InternalAttemptStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: InternalAttemptType = InternalAttemptType.AUTH,

    @Column(name = "request_payload", columnDefinition = "TEXT")
    var requestPayload: String? = null,

    @Column(name = "response_payload", columnDefinition = "TEXT")
    var responsePayload: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(nullable = false)
    var dispatched: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InternalAttempt) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
