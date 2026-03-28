package com.payment.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class InternalAttemptStatus {
    PENDING,
    SUCCESS,
    FAILURE,
    TIMEOUT,
    /** Scheduler forcibly closed this attempt after no webhook arrived within the deadline. */
    EXPIRED
}

enum class InternalAttemptType {
    AUTH,
    CAPTURE
}

@Entity
@Table(
    name = "internal_attempts",
    indexes = [
        Index(name = "idx_ia_attempt_id", columnList = "paymentAttemptId"),
        Index(name = "idx_ia_dispatched_status_created", columnList = "dispatched, status, createdAt"),
        Index(name = "idx_ia_type_status_created", columnList = "type, status, createdAt")
    ]
)
data class InternalAttempt(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val paymentAttemptId: String,

    @Column(nullable = false)
    val provider: String = "mock-visa",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InternalAttemptStatus = InternalAttemptStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: InternalAttemptType = InternalAttemptType.AUTH,

    @Column(columnDefinition = "TEXT")
    var requestPayload: String? = null,

    @Column(columnDefinition = "TEXT")
    var responsePayload: String? = null,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(nullable = false)
    var dispatched: Boolean = false,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
