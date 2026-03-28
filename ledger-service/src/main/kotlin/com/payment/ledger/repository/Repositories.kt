package com.payment.ledger.repository

import com.payment.ledger.entity.LedgerAccount
import com.payment.ledger.entity.LedgerEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface LedgerAccountRepository : JpaRepository<LedgerAccount, String> {
    fun findByName(name: String): LedgerAccount?
}

@Repository
interface LedgerEntryRepository : JpaRepository<LedgerEntry, String> {
    fun findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId: String): List<LedgerEntry>

    fun existsByPaymentIntentIdAndEventTypeAndEntryTypeAndLedgerAccountId(
        paymentIntentId: String,
        eventType: String,
        entryType: com.payment.ledger.entity.EntryType,
        ledgerAccountId: String
    ): Boolean

    @Query("""
        SELECT e.ledgerAccountId, e.entryType, SUM(e.amount)
        FROM LedgerEntry e
        GROUP BY e.ledgerAccountId, e.entryType
    """)
    fun findBalancesByAccount(): List<Array<Any>>
}
