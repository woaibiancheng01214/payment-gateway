package com.payment.gateway.service

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Service
class MerchantClient(
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${merchant.service.url:http://localhost:8087}") private val merchantServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(5))
        .build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "merchantServiceCircuitBreaker",
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

    private val merchantCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build<String, Boolean>()

    data class MerchantExistsResponse(val exists: Boolean)

    fun merchantExists(merchantId: String): Boolean {
        val cached = merchantCache.getIfPresent(merchantId)
        if (cached != null) return cached

        val result = try {
            CircuitBreaker.decorateSupplier(circuitBreaker) {
                val response = http.getForEntity(
                    "$merchantServiceUrl/internal/merchants/$merchantId/exists",
                    MerchantExistsResponse::class.java
                )
                response.body?.exists ?: false
            }.get()
        } catch (e: Exception) {
            log.error("Failed to check merchant existence for $merchantId: ${e.message}")
            throw IllegalStateException("Merchant service unavailable")
        }

        merchantCache.put(merchantId, result)
        return result
    }
}
