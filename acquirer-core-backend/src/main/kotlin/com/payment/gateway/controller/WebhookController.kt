package com.payment.gateway.controller

import com.payment.gateway.dto.WebhookRequest
import com.payment.gateway.service.PaymentIntentService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/webhooks")
class WebhookController(
    private val paymentIntentService: PaymentIntentService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/gateway")
    fun handleGatewayWebhook(@RequestBody request: WebhookRequest): ResponseEntity<Map<String, String>> {
        log.info("Received webhook: attemptId=${request.internalAttemptId}, status=${request.status}")
        paymentIntentService.handleWebhook(request.internalAttemptId, request.status)
        return ResponseEntity.ok(mapOf("received" to "true"))
    }
}
