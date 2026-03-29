package com.payment.token.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class CreatePaymentMethodRequest(
    val customerId: String? = null,

    @field:NotBlank(message = "Card data ID is required")
    val cardDataId: String,

    @field:NotBlank(message = "Card brand is required")
    val brand: String,

    @field:NotBlank(message = "Last 4 digits are required")
    @field:Pattern(regexp = "\\d{4}", message = "Last 4 must be exactly 4 digits")
    val last4: String,

    @field:Min(value = 1, message = "Expiry month must be between 1 and 12")
    @field:Max(value = 12, message = "Expiry month must be between 1 and 12")
    val expMonth: Int,

    @field:Min(value = 2025, message = "Expiry year must be current year or later")
    val expYear: Int
)

data class CreatePaymentMethodResponse(val paymentMethodId: String)

data class PaymentMethodBriefResponse(
    val id: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int,
    val status: String
)

data class PaymentMethodAuthResponse(
    val cardDataId: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int
)
