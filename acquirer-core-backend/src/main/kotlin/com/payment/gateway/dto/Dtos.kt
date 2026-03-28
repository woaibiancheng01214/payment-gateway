package com.payment.gateway.dto

import com.payment.gateway.entity.*
import java.time.Instant

data class CreatePaymentIntentRequest(
    val amount: Long,
    val currency: String
)

data class ConfirmPaymentIntentRequest(
    val paymentMethod: String = "card_4242"
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
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PaymentAttemptResponse(
    val id: String,
    val paymentIntentId: String,
    val paymentMethod: String,
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
    val createdAt: Instant,
    val updatedAt: Instant,
    val attempts: List<PaymentAttemptDetailResponse>
)

data class PaymentAttemptDetailResponse(
    val id: String,
    val paymentIntentId: String,
    val paymentMethod: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val internalAttempts: List<InternalAttemptResponse>
)

fun PaymentIntent.toResponse() = PaymentIntentResponse(
    id = id, amount = amount, currency = currency,
    status = status.name.lowercase(),
    createdAt = createdAt, updatedAt = updatedAt
)

fun PaymentAttempt.toResponse() = PaymentAttemptResponse(
    id = id, paymentIntentId = paymentIntentId,
    paymentMethod = paymentMethod,
    status = status.name.lowercase(),
    createdAt = createdAt, updatedAt = updatedAt
)

fun InternalAttempt.toResponse() = InternalAttemptResponse(
    id = id, paymentAttemptId = paymentAttemptId,
    provider = provider, status = status.name.lowercase(),
    type = type.name.lowercase(), retryCount = retryCount,
    requestPayload = requestPayload, responsePayload = responsePayload,
    createdAt = createdAt, updatedAt = updatedAt
)
