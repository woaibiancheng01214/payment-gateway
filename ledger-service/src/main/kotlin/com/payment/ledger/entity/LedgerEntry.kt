package com.payment.ledger.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class EntryType {
    DEBIT,
    CREDIT
}

@Entity
@Table(
    name = "ledger_entries",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_ledger_entry_dedup",
            columnNames = ["paymentIntentId", "eventType", "entryType", "ledgerAccountId"]
        )
    ]
)
data class LedgerEntry(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Column(nullable = false)
    val ledgerAccountId: String,

    @Column(nullable = false)
    val paymentIntentId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val entryType: EntryType,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false)
    val currency: String,

    @Column(nullable = false)
    val description: String,

    @Column(nullable = false)
    val eventType: String,

    @Column(nullable = false)
    val eventTimestamp: Instant,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
