package com.payment.auth.service

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
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
    private val http: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(5))
        .build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "pciTokenCircuitBreaker",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(15))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(5)
            .build()
    )

    data class PaymentMethodAuthResponse(val cardDataId: String, val brand: String, val expMonth: Int, val expYear: Int)

    fun getPaymentMethodForAuth(paymentMethodId: String): PaymentMethodAuthResponse {
        return CircuitBreaker.decorateSupplier(circuitBreaker) {
            val response = http.getForEntity(
                "$tokenServiceUrl/internal/payment-methods/$paymentMethodId/auth",
                PaymentMethodAuthResponse::class.java
            )
            response.body ?: throw IllegalStateException("Empty response from token-service for $paymentMethodId")
        }.get()
    }
}
