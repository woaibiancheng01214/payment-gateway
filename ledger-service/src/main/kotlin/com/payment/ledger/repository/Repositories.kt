package com.payment.ledger.repository

import com.payment.ledger.entity.DeadLetterEvent
import com.payment.ledger.entity.LedgerAccount
import com.payment.ledger.entity.LedgerEntry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
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
        SELECT a.name, e.entryType, SUM(e.amount)
        FROM LedgerEntry e JOIN LedgerAccount a ON e.ledgerAccountId = a.id
        GROUP BY a.name, e.entryType
    """)
    fun findBalancesWithAccountName(): List<Array<Any>>
}

@Repository
interface DeadLetterEventRepository : JpaRepository<DeadLetterEvent, String> {
    fun findByResolvedAtIsNullOrderByCreatedAtDesc(pageable: Pageable): Page<DeadLetterEvent>
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<DeadLetterEvent>
}
