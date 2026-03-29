package com.payment.gateway.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payment.gateway.entity.*
import java.time.Instant

data class CreatePaymentIntentRequest(
    val amount: Long,
    val currency: String,
    val description: String? = null,
    val statementDescriptor: String? = null,
    val metadata: Map<String, String>? = null,
    val customerEmail: String? = null,
    val customerId: String? = null
)

data class ConfirmPaymentIntentRequest(
    val cardNumber: String,
    val cardholderName: String? = null,
    val expiryMonth: Int,
    val expiryYear: Int,
    val cvc: String
)

data class WebhookRequest(
    val internalAttemptId: String,
    val status: String
)

data class PaymentIntentResponse(
    val id: String,
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
    val paymentToken: String,
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
    val paymentToken: String,
    val cardBrand: String?,
    val last4: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val internalAttempts: List<InternalAttemptResponse>
)

private val metadataMapper = jacksonObjectMapper()

fun PaymentIntent.toResponse() = PaymentIntentResponse(
    id = id, amount = amount, currency = currency,
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
    paymentToken = paymentToken,
    cardBrand = cardBrand,
    last4 = last4,
    status = status.name.lowercase(),
    createdAt = createdAt, updatedAt = updatedAt
)

fun PaymentIntent.toDetailResponse(attempts: List<PaymentAttemptDetailResponse>) = PaymentIntentDetailResponse(
    id = id, amount = amount, currency = currency,
    status = status.name.lowercase(),
    description = description,
    statementDescriptor = statementDescriptor,
    metadata = metadata?.let { metadataMapper.readValue<Map<String, String>>(it) },
    customerEmail = customerEmail,
    customerId = customerId,
    createdAt = createdAt, updatedAt = updatedAt,
    attempts = attempts
)

fun InternalAttempt.toResponse() = InternalAttemptResponse(
    id = id, paymentAttemptId = paymentAttemptId,
    provider = provider, status = status.name.lowercase(),
    type = type.name.lowercase(), retryCount = retryCount,
    requestPayload = requestPayload, responsePayload = responsePayload,
    createdAt = createdAt, updatedAt = updatedAt
)
