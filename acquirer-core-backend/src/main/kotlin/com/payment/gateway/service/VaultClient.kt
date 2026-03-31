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
class VaultClient(
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${vault.service.url:http://localhost:8083}") private val vaultServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "vaultServiceCircuitBreaker",
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
        "vaultServiceRetry",
        RetryConfig.custom<Any>()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
            .retryOnException { e -> e !is IllegalArgumentException && e !is IllegalStateException }
            .build()
    )

    data class CreateCardDataRequest(val pan: String, val expMonth: Int, val expYear: Int, val cardholderName: String?)
    data class CreateCardDataResponse(val cardDataId: String)

    private fun <T> withResilience(supplier: () -> T): T {
        return CircuitBreaker.decorateSupplier(circuitBreaker) {
            Retry.decorateSupplier(retry, supplier).get()
        }.get()
    }

    fun createCardData(pan: String, expMonth: Int, expYear: Int, cardholderName: String?): String {
        return withResilience {
            val response = http.postForEntity(
                "$vaultServiceUrl/internal/card-data",
                CreateCardDataRequest(pan, expMonth, expYear, cardholderName),
                CreateCardDataResponse::class.java
            )
            response.body?.cardDataId ?: throw IllegalStateException("Empty response from vault-service")
        }
    }
}
