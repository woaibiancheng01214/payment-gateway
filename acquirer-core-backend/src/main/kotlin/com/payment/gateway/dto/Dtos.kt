package com.payment.gateway.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payment.gateway.entity.Currency
import com.payment.gateway.entity.PaymentAttempt
import com.payment.gateway.entity.PaymentIntent
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreatePaymentIntentRequest(
    @field:NotBlank(message = "Merchant ID is required")
    val merchantId: String = "",

    @field:Min(value = 1, message = "Amount must be at least 1 (minor currency unit)")
    val amount: Long,

    @field:NotBlank(message = "Currency is required")
    val currency: String,

    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String? = null,

    @field:Size(max = 22, message = "Statement descriptor must be at most 22 characters")
    val statementDescriptor: String? = null,

    val metadata: Map<String, String>? = null,

    @field:Email(message = "Invalid email format")
    val customerEmail: String? = null,

    val customerId: String? = null
) {
    fun validatedCurrency(): Currency = Currency.fromString(currency)
}

data class ConfirmPaymentIntentRequest(
    @field:NotBlank(message = "Card number is required")
    val cardNumber: String,

    val cardholderName: String? = null,

    @field:Min(value = 1, message = "Expiry month must be between 1 and 12")
    @field:Max(value = 12, message = "Expiry month must be between 1 and 12")
    val expiryMonth: Int,

    @field:Min(value = 2025, message = "Expiry year must be current year or later")
    val expiryYear: Int,

    @field:NotBlank(message = "CVC is required")
    @field:Size(min = 3, max = 4, message = "CVC must be 3 or 4 digits")
    val cvc: String
)

data class WebhookRequest(
    val internalAttemptId: String,
    val status: String
)

data class PaymentIntentResponse(
    val id: String,
    val merchantId: String,
    val amount: Long,
    val currency: String,
    val status: String,
    val description: String?,
    val statementDescriptor: String?,
    val metadata: Map<String, String>?,
    val customerEmail: String?,
    val customerId: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PaymentAttemptResponse(
    val id: String,
    val paymentIntentId: String,
    val paymentMethodId: String,
    val cardBrand: String?,
    val last4: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class InternalAttemptResponse(
    val id: String,
    val paymentAttemptId: String,
    val provider: String,
    val status: String,
    val type: String,
    val retryCount: Int,
    val requestPayload: String?,
    val responsePayload: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PaymentIntentDetailResponse(
    val id: String,
    val merchantId: String,
    val amount: Long,
    val currency: String,
    val status: String,
    val description: String?,
    val statementDescriptor: String?,
    val metadata: Map<String, String>?,
    val customerEmail: String?,
    val customerId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val attempts: List<PaymentAttemptDetailResponse>
)

data class PaymentAttemptDetailResponse(
    val id: String,
    val paymentIntentId: String,
    val paymentMethodId: String,
    val cardBrand: String?,
    val last4: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val internalAttempts: List<InternalAttemptResponse>
)

private val metadataMapper = jacksonObjectMapper()

fun PaymentIntent.toResponse() = PaymentIntentResponse(
    id = id, merchantId = merchantId, amount = amount, currency = currency.name,
    status = status.name.lowercase(),
    description = description,
    statementDescriptor = statementDescriptor,
    metadata = metadata?.let { metadataMapper.readValue<Map<String, String>>(it) },
    customerEmail = customerEmail,
    customerId = customerId,
    createdAt = createdAt, updatedAt = updatedAt
)

fun PaymentAttempt.toResponse() = PaymentAttemptResponse(
    id = id, paymentIntentId = paymentIntentId,
    paymentMethodId = paymentMethodId,
    cardBrand = cardBrand?.name?.lowercase(),
    last4 = last4,
    status = status.name.lowercase(),
    createdAt = createdAt, updatedAt = updatedAt
)

fun PaymentIntent.toDetailResponse(attempts: List<PaymentAttemptDetailResponse>) = PaymentIntentDetailResponse(
    id = id, merchantId = merchantId, amount = amount, currency = currency.name,
    status = status.name.lowercase(),
    description = description,
    statementDescriptor = statementDescriptor,
    metadata = metadata?.let { metadataMapper.readValue<Map<String, String>>(it) },
    customerEmail = customerEmail,
    customerId = customerId,
    createdAt = createdAt, updatedAt = updatedAt,
    attempts = attempts
)
