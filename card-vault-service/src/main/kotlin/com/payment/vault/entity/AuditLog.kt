package com.payment.vault.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_log")
class AuditLog(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val action: String,

    @Column(name = "card_data_id", nullable = false)
    val cardDataId: String,

    @Column(name = "caller_service")
    val callerService: String? = null,

    @Column(name = "caller_ip")
    val callerIp: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuditLog) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
