package com.payment.ledger.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Consumes Debezium CDC events from the payment_intents table.
 *
 * Debezium envelope format:
 * {
 *   "before": { "id": "...", "status": "REQUIRES_CONFIRMATION", ... } | null,
 *   "after":  { "id": "...", "status": "AUTHORIZED", ... } | null,
 *   "op": "c" | "u" | "d" | "r",
 *   "ts_ms": 1234567890
 * }
 *
 * We only care about updates (op=u) where the status field changes to a
 * financially meaningful state (AUTHORIZED, CAPTURED, FAILED, EXPIRED).
 */
@Service
class PaymentEventConsumer(
    private val ledgerService: LedgerService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${ledger.kafka.topic:payment-gateway.public.payment_intents}"])
    fun consume(message: String) {
        try {
            val root = objectMapper.readTree(message)

            val payload = if (root.has("payload")) root["payload"] else root
            val op = payload["op"]?.asText() ?: return
            if (op != "u" && op != "c") return

            val after = payload["after"] ?: return
            val before = payload["before"]

            val intentId = extractString(after, "id") ?: return
            val newStatus = extractString(after, "status") ?: return
            val oldStatus = if (before != null && !before.isNull) extractString(before, "status") else null
            val amount = extractLong(after, "amount") ?: return
            val currency = extractString(after, "currency") ?: return
            val tsMs = payload["ts_ms"]?.asLong() ?: System.currentTimeMillis()
            val eventTimestamp = Instant.ofEpochMilli(tsMs)

            if (oldStatus == newStatus) return

            log.info("CDC event: intent=$intentId, $oldStatus -> $newStatus")
            processTransition(intentId, oldStatus, newStatus, amount, currency, eventTimestamp)
        } catch (e: Exception) {
            log.error("Failed to process CDC event: ${e.message}", e)
        }
    }

    private fun processTransition(
        intentId: String,
        oldStatus: String?,
        newStatus: String,
        amount: Long,
        currency: String,
        eventTimestamp: Instant
    ) {
        when (newStatus) {
            "AUTHORIZED" -> {
                ledgerService.postDoubleEntry(
                    paymentIntentId = intentId,
                    debitAccountName = "merchant_receivables",
                    creditAccountName = "gateway_payable",
                    amount = amount,
                    currency = currency,
                    eventType = "AUTHORIZED",
                    description = "Authorization hold for payment $intentId",
                    eventTimestamp = eventTimestamp
                )
            }

            "CAPTURED" -> {
                ledgerService.postDoubleEntry(
                    paymentIntentId = intentId,
                    debitAccountName = "gateway_payable",
                    creditAccountName = "merchant_revenue",
                    amount = amount,
                    currency = currency,
                    eventType = "CAPTURED",
                    description = "Capture settlement for payment $intentId",
                    eventTimestamp = eventTimestamp
                )
            }

            "FAILED" -> {
                if (oldStatus == "AUTHORIZED") {
                    ledgerService.postDoubleEntry(
                        paymentIntentId = intentId,
                        debitAccountName = "gateway_payable",
                        creditAccountName = "merchant_receivables",
                        amount = amount,
                        currency = currency,
                        eventType = "FAILED_REVERSAL",
                        description = "Reversal of authorization hold (failed) for payment $intentId",
                        eventTimestamp = eventTimestamp
                    )
                }
            }

            "EXPIRED" -> {
                if (oldStatus == "AUTHORIZED" || oldStatus == "REQUIRES_CONFIRMATION") {
                    val hasAuthEntry = ledgerService.getEntriesByPaymentIntent(intentId)
                        .any { it.eventType == "AUTHORIZED" }
                    if (hasAuthEntry) {
                        ledgerService.postDoubleEntry(
                            paymentIntentId = intentId,
                            debitAccountName = "gateway_payable",
                            creditAccountName = "merchant_receivables",
                            amount = amount,
                            currency = currency,
                            eventType = "EXPIRED_REVERSAL",
                            description = "Reversal of authorization hold (expired) for payment $intentId",
                            eventTimestamp = eventTimestamp
                        )
                    }
                }
            }

            else -> log.debug("Ignoring transition to $newStatus for $intentId")
        }
    }

    private fun extractString(node: JsonNode, field: String): String? =
        node[field]?.asText()

    private fun extractLong(node: JsonNode, field: String): Long? =
        if (node.has(field) && !node[field].isNull) node[field].asLong() else null
}
