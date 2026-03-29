package com.payment.gateway.mock.dto

data class AuthorizeRequest(
    val internalAttemptId: String,
    val paymentToken: String,
    val cardBrand: String,
    val amount: Long,
    val currency: String,
    val callbackUrl: String
)

data class CaptureRequest(
    val internalAttemptId: String,
    val amount: Long = 0,
    val currency: String = "USD",
    val callbackUrl: String
)

data class GatewayAckResponse(
    val gatewayRef: String,
    val status: String = "pending"
)

data class WebhookPayload(
    val internalAttemptId: String,
    val status: String
)
