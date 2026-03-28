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
data class LedgerAccount(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false, unique = true)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: AccountType,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
