package com.payment.auth.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class AuthoriseRequest(
    @field:NotBlank(message = "Payment intent ID is required")
    val paymentIntentId: String,

    @field:NotBlank(message = "Payment method ID is required")
    val paymentMethodId: String,

    @field:NotBlank(message = "Payment attempt ID is required")
    val paymentAttemptId: String,

    @field:Min(value = 1, message = "Amount must be at least 1")
    val amount: Long,

    @field:NotBlank(message = "Currency is required")
    val currency: String
)

data class AuthoriseResponse(val internalAttemptId: String, val status: String = "pending")

data class CaptureRequest(
    @field:NotBlank(message = "Payment attempt ID is required")
    val paymentAttemptId: String,

    @field:Min(value = 1, message = "Amount must be at least 1")
    val amount: Long,

    @field:NotBlank(message = "Currency is required")
    val currency: String
)

data class CaptureResponse(val internalAttemptId: String)

data class WebhookProcessRequest(
    @field:NotBlank(message = "Internal attempt ID is required")
    val internalAttemptId: String,

    @field:NotBlank(message = "Status is required")
    val status: String
)

data class WebhookProcessResponse(
    val type: String,
    val resolvedStatus: String,
    val paymentAttemptId: String,
    val shouldUpdate: Boolean
)

data class ExpireRequest(val paymentAttemptIds: List<String>)

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

data class ApiError(
    val type: String,
    val code: String,
    val message: String,
    val param: String? = null
)
