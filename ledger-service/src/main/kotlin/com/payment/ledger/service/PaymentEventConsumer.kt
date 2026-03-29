package com.payment.ledger.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payment.ledger.entity.DeadLetterEvent
import com.payment.ledger.repository.DeadLetterEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentIntentSnapshot(
    val id: String,
    val amount: Long,
    val currency: String,
    val status: String,
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("updated_at") val updatedAt: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DebeziumEnvelope(
    val before: PaymentIntentSnapshot? = null,
    val after: PaymentIntentSnapshot? = null,
    val op: String? = null,
    @JsonProperty("ts_ms")
    val tsMs: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DebeziumMessage(
    val payload: DebeziumEnvelope? = null
)

@Service
class PaymentEventConsumer(
    private val ledgerService: LedgerService,
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${ledger.kafka.topic:payment-gateway.public.payment_intents}"])
    fun consume(message: String) {
        try {
            val envelope = parseEnvelope(message) ?: return
            val op = envelope.op ?: return
            if (op != "u" && op != "c") return

            val after = envelope.after ?: return
            val oldStatus = envelope.before?.status

            // Use updatedAt from PaymentIntent as the authoritative event timestamp
            // This ensures correct ordering even for manually replayed events
            val eventTimestamp = when {
                after.updatedAt != null -> parseTimestamp(after.updatedAt)
                envelope.tsMs != null -> Instant.ofEpochMilli(envelope.tsMs)
                else -> Instant.now()
            }

            if (oldStatus == after.status) return

            log.info("CDC event: intent=${after.id}, $oldStatus -> ${after.status}")
            processTransition(after.id, oldStatus, after.status, after.amount, after.currency, eventTimestamp)
        } catch (e: Exception) {
            log.error("Failed to process CDC event: ${e.message}", e)
            // Persist to dead letter table for manual review/retry
            try {
                deadLetterEventRepository.save(
                    DeadLetterEvent(
                        topic = "payment_intents_cdc",
                        payload = message,
                        errorMessage = "${e.javaClass.simpleName}: ${e.message}"
                    )
                )
                log.info("Saved failed CDC event to dead_letter_events table")
            } catch (dlErr: Exception) {
                log.error("Failed to save dead letter event: ${dlErr.message}", dlErr)
            }
        }
    }

    /**
     * Parses timestamp from Debezium CDC. Depending on the Debezium converter config,
     * timestamps may arrive as ISO strings ("2026-03-29T07:48:14.927098Z") or
     * epoch microseconds. Handles both formats.
     */
    private fun parseTimestamp(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            try {
                val micros = value.toLong()
                Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000)
            } catch (e2: Exception) {
                Instant.now()
            }
        }

    private fun parseEnvelope(message: String): DebeziumEnvelope? {
        val wrapped = objectMapper.readValue<DebeziumMessage>(message)
        if (wrapped.payload != null) return wrapped.payload
        return objectMapper.readValue<DebeziumEnvelope>(message)
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
}
