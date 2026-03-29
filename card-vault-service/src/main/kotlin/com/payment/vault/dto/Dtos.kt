package com.payment.vault.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateCardDataRequest(
    @field:NotBlank(message = "PAN is required")
    @field:Pattern(regexp = "\\d{13,19}", message = "PAN must be 13-19 digits")
    val pan: String,

    @field:Min(value = 1, message = "Expiry month must be between 1 and 12")
    @field:Max(value = 12, message = "Expiry month must be between 1 and 12")
    val expMonth: Int,

    @field:Min(value = 2025, message = "Expiry year must be current year or later")
    val expYear: Int,

    @field:Size(max = 100, message = "Cardholder name must be at most 100 characters")
    val cardholderName: String? = null
)

data class CreateCardDataResponse(val cardDataId: String)

data class CardDataResponse(
    val pan: String,
    val expMonth: Int,
    val expYear: Int
)
