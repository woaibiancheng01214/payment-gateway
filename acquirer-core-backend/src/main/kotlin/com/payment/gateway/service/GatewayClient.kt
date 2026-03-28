package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.gateway.entity.InternalAttempt
import com.payment.gateway.entity.InternalAttemptType
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
    @Value("\${gateway.callback.url:http://localhost:8080/webhooks/gateway}") private val callbackUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(6))
        .setReadTimeout(Duration.ofSeconds(10))
        .build()

    fun dispatch(internalAttempt: InternalAttempt) {
        when (internalAttempt.type) {
            InternalAttemptType.AUTH -> authorize(internalAttempt)
            InternalAttemptType.CAPTURE -> capture(internalAttempt)
        }
    }

    private fun authorize(internalAttempt: InternalAttempt) {
        val payload = parsePayload(internalAttempt.requestPayload)
        val body = mapOf(
            "internalAttemptId" to internalAttempt.id,
            "paymentMethod" to (payload["paymentMethod"] ?: "card_4242"),
            "amount" to (payload["amount"] ?: 0L),
            "currency" to (payload["currency"] ?: "USD"),
            "callbackUrl" to callbackUrl
        )
        http.postForEntity("$gatewayUrl/v1/authorize", body, Map::class.java)
        log.info("AUTH dispatched to gateway-server for attempt ${internalAttempt.id}")
    }

    private fun capture(internalAttempt: InternalAttempt) {
        val body = mapOf(
            "internalAttemptId" to internalAttempt.id,
            "callbackUrl" to callbackUrl
        )
        http.postForEntity("$gatewayUrl/v1/capture", body, Map::class.java)
        log.info("CAPTURE dispatched to gateway-server for attempt ${internalAttempt.id}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePayload(json: String?): Map<String, Any> =
        if (json.isNullOrBlank()) emptyMap()
        else objectMapper.readValue(json, Map::class.java) as Map<String, Any>
}
