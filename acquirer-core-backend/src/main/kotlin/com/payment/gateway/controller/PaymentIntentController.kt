package com.payment.gateway.controller

import com.payment.gateway.dto.*
import com.payment.gateway.service.PaymentIntentService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/payment_intents")
class PaymentIntentController(
    private val paymentIntentService: PaymentIntentService
) {

    @PostMapping
    fun create(
        @RequestBody request: CreatePaymentIntentRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?
    ): ResponseEntity<PaymentIntentResponse> {
        val response = paymentIntentService.createPaymentIntent(request, idempotencyKey)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: String,
        @RequestBody(required = false) request: ConfirmPaymentIntentRequest?
    ): ResponseEntity<PaymentIntentResponse> {
        val body = request ?: ConfirmPaymentIntentRequest()
        val response = paymentIntentService.confirmPaymentIntent(id, body)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/{id}/capture")
    fun capture(@PathVariable id: String): ResponseEntity<PaymentIntentResponse> {
        val response = paymentIntentService.capturePaymentIntent(id)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{id}")
    fun getDetail(@PathVariable id: String): ResponseEntity<PaymentIntentDetailResponse> {
        val response = paymentIntentService.getPaymentIntentDetail(id)
        return ResponseEntity.ok(response)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20) pageable: Pageable): ResponseEntity<Page<PaymentIntentResponse>> {
        return ResponseEntity.ok(paymentIntentService.listPaymentIntents(pageable))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Bad request")))

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to (e.message ?: "Conflict")))
}
