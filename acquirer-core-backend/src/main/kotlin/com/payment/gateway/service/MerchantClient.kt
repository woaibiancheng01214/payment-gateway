package com.payment.gateway.service

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

    data class MerchantExistsResponse(val exists: Boolean)

    fun merchantExists(merchantId: String): Boolean {
        return try {
            val response = http.getForEntity(
                "$merchantServiceUrl/internal/merchants/$merchantId/exists",
                MerchantExistsResponse::class.java
            )
            response.body?.exists ?: false
        } catch (e: Exception) {
            log.error("Failed to check merchant existence for $merchantId: ${e.message}")
            throw IllegalStateException("Merchant service unavailable")
        }
    }
}
