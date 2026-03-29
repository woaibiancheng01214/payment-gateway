package com.payment.gateway.controller

import com.payment.gateway.dto.*
import com.payment.gateway.service.PaymentIntentService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/payment_intents")
class PaymentIntentController(
    private val paymentIntentService: PaymentIntentService
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreatePaymentIntentRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?
    ): ResponseEntity<PaymentIntentResponse> {
        val response = paymentIntentService.createPaymentIntent(request, idempotencyKey)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: String,
        @Valid @RequestBody request: ConfirmPaymentIntentRequest
    ): ResponseEntity<PaymentIntentResponse> {
        val response = paymentIntentService.confirmPaymentIntent(id, request)
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

    @GetMapping("/merchant/{merchantId}")
    fun listByMerchant(
        @PathVariable merchantId: String,
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PaymentIntentResponse>> {
        return ResponseEntity.ok(paymentIntentService.listPaymentIntentsByMerchant(merchantId, pageable))
    }

    // ── Cursor-based pagination (O(1) regardless of page depth) ────────────

    @GetMapping("/cursor")
    fun listCursor(
        @RequestParam(name = "starting_after", required = false) startingAfter: String?,
        @RequestParam(name = "limit", defaultValue = "20") limit: Int
    ): ResponseEntity<PaymentIntentService.CursorPageResponse> {
        return ResponseEntity.ok(paymentIntentService.listPaymentIntentsCursor(startingAfter, limit))
    }

    @GetMapping("/cursor/merchant/{merchantId}")
    fun listByMerchantCursor(
        @PathVariable merchantId: String,
        @RequestParam(name = "starting_after", required = false) startingAfter: String?,
        @RequestParam(name = "limit", defaultValue = "20") limit: Int
    ): ResponseEntity<PaymentIntentService.CursorPageResponse> {
        return ResponseEntity.ok(paymentIntentService.listPaymentIntentsByMerchantCursor(merchantId, startingAfter, limit))
    }
}
