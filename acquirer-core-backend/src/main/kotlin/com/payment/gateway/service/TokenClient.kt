package com.payment.gateway.service

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Service
class TokenClient(
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${token.service.url:http://localhost:8084}") private val tokenServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "tokenServiceCircuitBreaker",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .failureRateThreshold(50f)
            .slowCallRateThreshold(80f)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(15))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(10)
            .build()
    )

    private val retry: Retry = Retry.of(
        "tokenServiceRetry",
        RetryConfig.custom<Any>()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
            .retryOnException { e -> e !is IllegalArgumentException && e !is IllegalStateException }
            .build()
    )

    data class CreatePaymentMethodRequest(
        val customerId: String?,
        val cardDataId: String,
        val brand: String,
        val last4: String,
        val expMonth: Int,
        val expYear: Int
    )
    data class CreatePaymentMethodResponse(val paymentMethodId: String)
    data class PaymentMethodBriefResponse(val id: String, val brand: String, val last4: String, val expMonth: Int, val expYear: Int, val status: String)

    private fun <T> withResilience(supplier: () -> T): T {
        return CircuitBreaker.decorateSupplier(circuitBreaker) {
            Retry.decorateSupplier(retry, supplier).get()
        }.get()
    }

    fun createPaymentMethod(customerId: String?, cardDataId: String, brand: String, last4: String, expMonth: Int, expYear: Int): String {
        return withResilience {
            val response = http.postForEntity(
                "$tokenServiceUrl/internal/payment-methods",
                CreatePaymentMethodRequest(customerId, cardDataId, brand, last4, expMonth, expYear),
                CreatePaymentMethodResponse::class.java
            )
            response.body?.paymentMethodId ?: throw IllegalStateException("Empty response from token-service")
        }
    }

    fun getPaymentMethodBrief(paymentMethodId: String): PaymentMethodBriefResponse {
        return withResilience {
            val response = http.getForEntity(
                "$tokenServiceUrl/internal/payment-methods/$paymentMethodId/brief",
                PaymentMethodBriefResponse::class.java
            )
            response.body ?: throw IllegalStateException("Empty response from token-service for $paymentMethodId")
        }
    }
}
