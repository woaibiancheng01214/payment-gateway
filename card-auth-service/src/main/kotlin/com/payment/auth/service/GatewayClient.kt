package com.payment.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.auth.entity.InternalAttempt
import com.payment.auth.entity.InternalAttemptType
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Service
class GatewayClient(
    private val objectMapper: ObjectMapper,
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${gateway.server.url:http://localhost:8081}") private val gatewayUrl: String,
    @Value("\${gateway.callback.url:http://localhost:8080/v1/webhooks/gateway}") private val callbackUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "gatewayCircuitBreaker",
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

    private val bulkhead: Bulkhead = Bulkhead.of(
        "gatewayBulkhead",
        BulkheadConfig.custom()
            .maxConcurrentCalls(20)
            .maxWaitDuration(Duration.ofMillis(500))
            .build()
    )

    fun dispatch(internalAttempt: InternalAttempt) {
        Bulkhead.decorateRunnable(bulkhead,
            CircuitBreaker.decorateRunnable(circuitBreaker) {
                when (internalAttempt.type) {
                    InternalAttemptType.AUTH -> authorize(internalAttempt)
                    InternalAttemptType.CAPTURE -> capture(internalAttempt)
                }
            }
        ).run()
    }

    private fun authorize(internalAttempt: InternalAttempt) {
        val payload = parsePayload(internalAttempt.requestPayload)
        val body = mapOf(
            "internalAttemptId" to internalAttempt.id,
            "paymentToken" to (payload["paymentToken"] ?: ""),
            "cardBrand" to (payload["cardBrand"] ?: "unknown"),
            "amount" to (payload["amount"] ?: 0L),
            "currency" to (payload["currency"] ?: "USD"),
            "callbackUrl" to callbackUrl
        )
        http.postForEntity("$gatewayUrl/v1/authorize", body, Map::class.java)
        log.info("AUTH dispatched to gateway for attempt ${internalAttempt.id}")
    }

    private fun capture(internalAttempt: InternalAttempt) {
        val payload = parsePayload(internalAttempt.requestPayload)
        val body = mapOf(
            "internalAttemptId" to internalAttempt.id,
            "amount" to (payload["amount"] ?: 0L),
            "currency" to (payload["currency"] ?: "USD"),
            "callbackUrl" to callbackUrl
        )
        http.postForEntity("$gatewayUrl/v1/capture", body, Map::class.java)
        log.info("CAPTURE dispatched to gateway for attempt ${internalAttempt.id}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePayload(json: String?): Map<String, Any> =
        if (json.isNullOrBlank()) emptyMap()
        else objectMapper.readValue(json, Map::class.java) as Map<String, Any>
}
