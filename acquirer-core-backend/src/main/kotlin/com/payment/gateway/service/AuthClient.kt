package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant

@Service
class AuthClient(
    private val objectMapper: ObjectMapper,
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${auth.service.url:http://localhost:8085}") private val authServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "authServiceCircuitBreaker",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .failureRateThreshold(50f)
            .slowCallRateThreshold(80f)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(10)
            .build()
    )

    private val retry: Retry = Retry.of(
        "authServiceRetry",
        RetryConfig.custom<Any>()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
            .retryOnException { e -> e !is IllegalArgumentException && e !is IllegalStateException }
            .build()
    )

    data class AuthoriseRequest(val paymentIntentId: String, val paymentMethodId: String, val paymentAttemptId: String, val amount: Long, val currency: String)
    data class AuthoriseResponse(val internalAttemptId: String, val status: String = "pending")
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

    private fun <T> withResilience(supplier: () -> T): T {
        return CircuitBreaker.decorateSupplier(circuitBreaker) {
            Retry.decorateSupplier(retry, supplier).get()
        }.get()
    }

    fun authorise(paymentIntentId: String, paymentMethodId: String, paymentAttemptId: String, amount: Long, currency: String): AuthoriseResponse {
        return withResilience {
            val response = http.postForEntity(
                "$authServiceUrl/internal/auth/authorise",
                AuthoriseRequest(paymentIntentId, paymentMethodId, paymentAttemptId, amount, currency),
                AuthoriseResponse::class.java
            )
            response.body ?: throw IllegalStateException("Empty response from auth-service")
        }
    }

    fun capture(paymentAttemptId: String, amount: Long, currency: String): CaptureResponse {
        return withResilience {
            val response = http.postForEntity(
                "$authServiceUrl/internal/auth/capture",
                CaptureRequest(paymentAttemptId, amount, currency),
                CaptureResponse::class.java
            )
            response.body ?: throw IllegalStateException("Empty response from auth-service")
        }
    }

    fun processWebhook(internalAttemptId: String, status: String): WebhookProcessResponse {
        return withResilience {
            val response = http.postForEntity(
                "$authServiceUrl/internal/auth/webhook",
                WebhookProcessRequest(internalAttemptId, status),
                WebhookProcessResponse::class.java
            )
            response.body ?: throw IllegalStateException("Empty response from auth-service webhook")
        }
    }

    fun expireAttempts(paymentAttemptIds: List<String>) {
        if (paymentAttemptIds.isEmpty()) return
        try {
            withResilience {
                http.postForEntity(
                    "$authServiceUrl/internal/auth/expire",
                    ExpireRequest(paymentAttemptIds),
                    Map::class.java
                )
            }
        } catch (e: Exception) {
            log.error("Failed to expire attempts in auth-service: ${e.message}")
        }
    }

    fun getAttemptsBatch(paymentAttemptIds: List<String>): Map<String, List<InternalAttemptResponse>> {
        if (paymentAttemptIds.isEmpty()) return emptyMap()
        return withResilience {
            val typeRef = object : ParameterizedTypeReference<Map<String, List<InternalAttemptResponse>>>() {}
            val response = http.exchange(
                "$authServiceUrl/internal/auth/attempts/batch",
                HttpMethod.POST,
                HttpEntity(paymentAttemptIds),
                typeRef
            )
            response.body ?: emptyMap()
        }
    }
}
