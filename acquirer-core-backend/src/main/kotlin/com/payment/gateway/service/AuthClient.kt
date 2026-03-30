package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Instant

@Service
class AuthClient(
    private val objectMapper: ObjectMapper,
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${auth.service.url:http://localhost:8085}") private val authServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

    data class ConfirmRequest(val paymentIntentId: String, val paymentMethodId: String, val paymentAttemptId: String, val amount: Long, val currency: String)
    data class ConfirmResponse(val internalAttemptId: String)
    data class CaptureRequest(val paymentAttemptId: String, val amount: Long, val currency: String)
    data class CaptureResponse(val internalAttemptId: String)
    data class WebhookProcessRequest(val internalAttemptId: String, val status: String)
    data class WebhookProcessResponse(val type: String, val resolvedStatus: String, val paymentAttemptId: String, val shouldUpdate: Boolean)
    data class ExpireRequest(val paymentAttemptIds: List<String>)
    data class InternalAttemptResponse(
        val id: String, val paymentAttemptId: String, val provider: String,
        val status: String, val type: String, val retryCount: Int,
        val requestPayload: String?, val responsePayload: String?,
        val createdAt: Instant, val updatedAt: Instant
    )

    fun confirm(paymentIntentId: String, paymentMethodId: String, paymentAttemptId: String, amount: Long, currency: String): ConfirmResponse {
        val response = http.postForEntity(
            "$authServiceUrl/internal/auth/confirm",
            ConfirmRequest(paymentIntentId, paymentMethodId, paymentAttemptId, amount, currency),
            ConfirmResponse::class.java
        )
        return response.body ?: throw IllegalStateException("Empty response from auth-service")
    }

    fun capture(paymentAttemptId: String, amount: Long, currency: String): CaptureResponse {
        val response = http.postForEntity(
            "$authServiceUrl/internal/auth/capture",
            CaptureRequest(paymentAttemptId, amount, currency),
            CaptureResponse::class.java
        )
        return response.body ?: throw IllegalStateException("Empty response from auth-service")
    }

    fun processWebhook(internalAttemptId: String, status: String): WebhookProcessResponse {
        val response = http.postForEntity(
            "$authServiceUrl/internal/auth/webhook",
            WebhookProcessRequest(internalAttemptId, status),
            WebhookProcessResponse::class.java
        )
        return response.body ?: throw IllegalStateException("Empty response from auth-service webhook")
    }

    fun expireAttempts(paymentAttemptIds: List<String>) {
        if (paymentAttemptIds.isEmpty()) return
        try {
            http.postForEntity(
                "$authServiceUrl/internal/auth/expire",
                ExpireRequest(paymentAttemptIds),
                Map::class.java
            )
        } catch (e: Exception) {
            log.error("Failed to expire attempts in auth-service: ${e.message}")
        }
    }

    fun getAttemptsBatch(paymentAttemptIds: List<String>): Map<String, List<InternalAttemptResponse>> {
        if (paymentAttemptIds.isEmpty()) return emptyMap()
        val typeRef = object : ParameterizedTypeReference<Map<String, List<InternalAttemptResponse>>>() {}
        val response = http.exchange(
            "$authServiceUrl/internal/auth/attempts/batch",
            HttpMethod.POST,
            HttpEntity(paymentAttemptIds),
            typeRef
        )
        return response.body ?: emptyMap()
    }
}
