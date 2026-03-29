package com.payment.ledger.controller

import com.payment.ledger.entity.LedgerEntry
import com.payment.ledger.service.LedgerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/ledger")
class LedgerController(
    private val ledgerService: LedgerService
) {
    @GetMapping("/entries")
    fun getEntries(@RequestParam paymentIntentId: String): ResponseEntity<List<LedgerEntry>> =
        ResponseEntity.ok(ledgerService.getEntriesByPaymentIntent(paymentIntentId))

    @GetMapping("/balances")
    fun getBalances(): ResponseEntity<Map<String, Map<String, Long>>> =
        ResponseEntity.ok(ledgerService.getBalances())
}
