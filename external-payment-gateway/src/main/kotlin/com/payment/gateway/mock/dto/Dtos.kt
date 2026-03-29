package com.payment.gateway.mock.dto

/**
 * Sent by the orchestrator to /v1/authorize.
 */
data class AuthorizeRequest(
    /** Correlation ID — echoed back in the webhook payload so the orchestrator can route it. */
    val internalAttemptId: String,
    /** Opaque token from the token vault — the gateway never sees the raw PAN. */
    val paymentToken: String,
    /** Card brand derived from BIN (e.g., "visa", "mastercard"). Used for behavior routing. */
    val cardBrand: String,
    val amount: Long,
    val currency: String,
    /** Where to POST the outcome webhook once processing is complete. */
    val callbackUrl: String
)

/**
 * Sent by the orchestrator to /v1/capture.
 */
data class CaptureRequest(
    val internalAttemptId: String,
    val callbackUrl: String
)

/**
 * Immediate synchronous response — the gateway has accepted the job.
 */
data class GatewayAckResponse(
    val gatewayRef: String,
    val status: String = "pending"
)

/**
 * Webhook payload fired back to the orchestrator's callbackUrl.
 */
data class WebhookPayload(
    val internalAttemptId: String,
    /** "success" | "failure" — timeouts are retried internally; only terminal states are delivered. */
    val status: String
)
