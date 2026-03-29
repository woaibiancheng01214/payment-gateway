package com.payment.token.controller

import com.payment.token.dto.CreatePaymentMethodRequest
import com.payment.token.dto.CreatePaymentMethodResponse
import com.payment.token.dto.PaymentMethodAuthResponse
import com.payment.token.dto.PaymentMethodBriefResponse
import com.payment.token.entity.CardBrand
import com.payment.token.service.PaymentMethodService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/internal/payment-methods")
class TokenInternalController(
    private val paymentMethodService: PaymentMethodService
) {

    @PostMapping
    fun createPaymentMethod(
        @Valid @RequestBody request: CreatePaymentMethodRequest
    ): ResponseEntity<CreatePaymentMethodResponse> {
        val brand = try {
            CardBrand.valueOf(request.brand.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid card brand: ${request.brand}")
        }
        val id = paymentMethodService.createPaymentMethod(
            customerId = request.customerId,
            cardDataId = request.cardDataId,
            brand = brand,
            last4 = request.last4,
            expMonth = request.expMonth,
            expYear = request.expYear
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CreatePaymentMethodResponse(paymentMethodId = id))
    }

    @GetMapping("/{id}/brief")
    fun getPaymentMethodBrief(@PathVariable id: String): ResponseEntity<PaymentMethodBriefResponse> {
        return ResponseEntity.ok(paymentMethodService.getPaymentMethodBrief(id))
    }

    @GetMapping("/{id}/auth")
    fun getPaymentMethodForAuth(@PathVariable id: String): ResponseEntity<PaymentMethodAuthResponse> {
        return ResponseEntity.ok(paymentMethodService.getPaymentMethodForAuth(id))
    }
}
