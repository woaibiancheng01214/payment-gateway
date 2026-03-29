package com.payment.auth.dto

import java.time.Instant

data class ConfirmRequest(
    val paymentIntentId: String,
    val paymentMethodId: String,
    val paymentAttemptId: String,
    val amount: Long,
    val currency: String
)

data class ConfirmResponse(val internalAttemptId: String)

data class CaptureRequest(
    val paymentAttemptId: String,
    val amount: Long,
    val currency: String
)

data class CaptureResponse(val internalAttemptId: String)

data class WebhookProcessRequest(
    val internalAttemptId: String,
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
