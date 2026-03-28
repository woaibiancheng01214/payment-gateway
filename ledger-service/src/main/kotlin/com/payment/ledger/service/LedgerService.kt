package com.payment.ledger.service

import com.payment.ledger.entity.*
import com.payment.ledger.repository.LedgerAccountRepository
import com.payment.ledger.repository.LedgerEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LedgerService(
    private val accountRepository: LedgerAccountRepository,
    private val entryRepository: LedgerEntryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun postDoubleEntry(
        paymentIntentId: String,
        debitAccountName: String,
        creditAccountName: String,
        amount: Long,
        currency: String,
        eventType: String,
        description: String,
        eventTimestamp: Instant
    ) {
        val debitAccount = accountRepository.findByName(debitAccountName)
            ?: throw IllegalStateException("Ledger account '$debitAccountName' not found")
        val creditAccount = accountRepository.findByName(creditAccountName)
            ?: throw IllegalStateException("Ledger account '$creditAccountName' not found")

        if (entryRepository.existsByPaymentIntentIdAndEventTypeAndEntryTypeAndLedgerAccountId(
                paymentIntentId, eventType, EntryType.DEBIT, debitAccount.id
            )
        ) {
            log.info("Duplicate ledger entry skipped: $paymentIntentId/$eventType")
            return
        }

        val debitEntry = LedgerEntry(
            ledgerAccountId = debitAccount.id,
            paymentIntentId = paymentIntentId,
            entryType = EntryType.DEBIT,
            amount = amount,
            currency = currency,
            description = description,
            eventType = eventType,
            eventTimestamp = eventTimestamp
        )
        val creditEntry = LedgerEntry(
            ledgerAccountId = creditAccount.id,
            paymentIntentId = paymentIntentId,
            entryType = EntryType.CREDIT,
            amount = amount,
            currency = currency,
            description = description,
            eventType = eventType,
            eventTimestamp = eventTimestamp
        )

        entryRepository.save(debitEntry)
        entryRepository.save(creditEntry)
        log.info("Posted ledger entries for $paymentIntentId: $eventType ($debitAccountName -> $creditAccountName, $amount $currency)")
    }

    fun getEntriesByPaymentIntent(paymentIntentId: String): List<LedgerEntry> =
        entryRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId)

    fun getBalances(): Map<String, Map<String, Long>> {
        val raw = entryRepository.findBalancesWithAccountName()
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        for (row in raw) {
            val accountName = row[0] as String
            val entryType = (row[1] as EntryType).name
            val sum = row[2] as Long
            result.getOrPut(accountName) { mutableMapOf() }[entryType] = sum
        }
        return result
    }
}
