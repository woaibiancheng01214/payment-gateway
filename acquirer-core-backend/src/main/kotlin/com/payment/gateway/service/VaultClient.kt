package com.payment.gateway.service

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
    private val http: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(5))
        .build()

    data class CreateCardDataRequest(val pan: String, val expMonth: Int, val expYear: Int, val cardholderName: String?)
    data class CreateCardDataResponse(val cardDataId: String)

    fun createCardData(pan: String, expMonth: Int, expYear: Int, cardholderName: String?): String {
        val response = http.postForEntity(
            "$vaultServiceUrl/internal/card-data",
            CreateCardDataRequest(pan, expMonth, expYear, cardholderName),
            CreateCardDataResponse::class.java
        )
        return response.body?.cardDataId ?: throw IllegalStateException("Empty response from vault-service")
    }
}
