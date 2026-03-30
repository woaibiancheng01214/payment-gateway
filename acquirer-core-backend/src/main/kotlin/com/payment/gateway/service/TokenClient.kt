package com.payment.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class TokenClient(
    restTemplateBuilder: RestTemplateBuilder,
    @Value("\${token.service.url:http://localhost:8084}") private val tokenServiceUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestTemplate = restTemplateBuilder.build()

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

    fun createPaymentMethod(customerId: String?, cardDataId: String, brand: String, last4: String, expMonth: Int, expYear: Int): String {
        val response = http.postForEntity(
            "$tokenServiceUrl/internal/payment-methods",
            CreatePaymentMethodRequest(customerId, cardDataId, brand, last4, expMonth, expYear),
            CreatePaymentMethodResponse::class.java
        )
        return response.body?.paymentMethodId ?: throw IllegalStateException("Empty response from token-service")
    }

    fun getPaymentMethodBrief(paymentMethodId: String): PaymentMethodBriefResponse {
        val response = http.getForEntity(
            "$tokenServiceUrl/internal/payment-methods/$paymentMethodId/brief",
            PaymentMethodBriefResponse::class.java
        )
        return response.body ?: throw IllegalStateException("Empty response from token-service for $paymentMethodId")
    }
}
