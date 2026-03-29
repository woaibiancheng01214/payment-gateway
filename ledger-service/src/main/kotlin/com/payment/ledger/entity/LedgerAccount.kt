package com.payment.ledger.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}

@Entity
@Table(name = "ledger_accounts")
class LedgerAccount(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false, unique = true)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: AccountType,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LedgerAccount) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "LedgerAccount(id=$id, name=$name, type=$type)"
}
