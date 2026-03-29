package com.payment.ledger.controller

import com.payment.ledger.entity.DeadLetterEvent
import com.payment.ledger.repository.DeadLetterEventRepository
import com.payment.ledger.service.PaymentEventConsumer
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/v1/ledger/dead-letter-events")
class DeadLetterController(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val paymentEventConsumer: PaymentEventConsumer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false, defaultValue = "false") unresolvedOnly: Boolean
    ): ResponseEntity<Page<DeadLetterEvent>> {
        val page = if (unresolvedOnly) {
            deadLetterEventRepository.findByResolvedAtIsNullOrderByCreatedAtDesc(pageable)
        } else {
            deadLetterEventRepository.findAllByOrderByCreatedAtDesc(pageable)
        }
        return ResponseEntity.ok(page)
    }

    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        val event = deadLetterEventRepository.findById(id)
            .orElseThrow { IllegalArgumentException("DeadLetterEvent $id not found") }

        try {
            paymentEventConsumer.consume(event.payload)
            event.resolvedAt = Instant.now()
            deadLetterEventRepository.save(event)
            log.info("Successfully retried dead letter event $id")
            return ResponseEntity.ok(mapOf("status" to "retried", "id" to id))
        } catch (e: Exception) {
            log.error("Retry failed for dead letter event $id: ${e.message}")
            return ResponseEntity.badRequest().body(mapOf("status" to "retry_failed", "error" to (e.message ?: "Unknown error")))
        }
    }
}
