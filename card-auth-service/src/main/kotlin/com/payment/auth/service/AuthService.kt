package com.payment.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.auth.dto.*
import com.payment.auth.entity.*
import com.payment.auth.repository.InternalAttemptRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AuthService(
    private val internalAttemptRepository: InternalAttemptRepository,
    private val gatewayClient: GatewayClient,
    private val tokenClient: TokenClient,
    private val vaultClient: VaultClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun authorise(request: AuthoriseRequest): AuthoriseResponse {
        val pmAuth = tokenClient.getPaymentMethodForAuth(request.paymentMethodId)
        // Verify card data is accessible (validates the card_data_id is real)
        vaultClient.getCardData(pmAuth.cardDataId)

        val internalAttempt = InternalAttempt(
            paymentAttemptId = request.paymentAttemptId,
            type = InternalAttemptType.AUTH,
            requestPayload = truncatePayload(objectMapper.writeValueAsString(
                mapOf(
                    "paymentToken" to request.paymentMethodId,
                    "cardBrand" to pmAuth.brand,
                    "amount" to request.amount,
                    "currency" to request.currency
                )
            ))
        )
        internalAttemptRepository.save(internalAttempt)

        // Synchronous auth — gateway returns result inline
        val status = try {
            val result = gatewayClient.dispatchAuth(internalAttempt)
            markDispatched(internalAttempt.id)
            val resolvedStatus = when (result) {
                "success" -> InternalAttemptStatus.SUCCESS
                else -> InternalAttemptStatus.FAILURE
            }
            internalAttempt.status = resolvedStatus
            internalAttempt.responsePayload = truncatePayload(objectMapper.writeValueAsString(mapOf("status" to result)))
            internalAttempt.updatedAt = Instant.now()
            internalAttemptRepository.save(internalAttempt)
            result
        } catch (e: Exception) {
            log.warn("Auth dispatch failed for ${internalAttempt.id} — scheduler will retry: ${e.message}")
            "pending"
        }
        return AuthoriseResponse(internalAttemptId = internalAttempt.id, status = status)
    }

    fun capture(request: CaptureRequest): CaptureResponse {
        val alreadyCapturing = internalAttemptRepository.findByPaymentAttemptId(request.paymentAttemptId)
            .any { it.type == InternalAttemptType.CAPTURE }
        if (alreadyCapturing) {
            log.warn("Duplicate capture blocked for attempt ${request.paymentAttemptId}")
            val existing = internalAttemptRepository.findByPaymentAttemptId(request.paymentAttemptId)
                .first { it.type == InternalAttemptType.CAPTURE }
            return CaptureResponse(internalAttemptId = existing.id)
        }

        val internalAttempt = InternalAttempt(
            paymentAttemptId = request.paymentAttemptId,
            type = InternalAttemptType.CAPTURE,
            requestPayload = truncatePayload(objectMapper.writeValueAsString(
                mapOf(
                    "paymentAttemptId" to request.paymentAttemptId,
                    "action" to "capture",
                    "amount" to request.amount,
                    "currency" to request.currency
                )
            ))
        )
        internalAttemptRepository.save(internalAttempt)

        dispatchCaptureAsync(internalAttempt)
        return CaptureResponse(internalAttemptId = internalAttempt.id)
    }

    @Transactional
    fun processWebhook(request: WebhookProcessRequest): WebhookProcessResponse {
        val internalAttempt = internalAttemptRepository.findById(request.internalAttemptId)
            .orElseThrow { IllegalArgumentException("InternalAttempt ${request.internalAttemptId} not found") }

        val terminalStatuses = setOf(InternalAttemptStatus.SUCCESS, InternalAttemptStatus.FAILURE, InternalAttemptStatus.EXPIRED)
        if (internalAttempt.status in terminalStatuses) {
            log.warn("Ignoring webhook for terminal InternalAttempt ${request.internalAttemptId} (status=${internalAttempt.status})")
            return WebhookProcessResponse(
                type = internalAttempt.type.name.lowercase(),
                resolvedStatus = internalAttempt.status.name.lowercase(),
                paymentAttemptId = internalAttempt.paymentAttemptId,
                shouldUpdate = false
            )
        }

        val resolvedStatus = when (request.status.lowercase()) {
            "success" -> InternalAttemptStatus.SUCCESS
            "failure" -> InternalAttemptStatus.FAILURE
            "timeout" -> InternalAttemptStatus.TIMEOUT
            else -> throw IllegalArgumentException("Unknown gateway status: ${request.status}")
        }

        internalAttempt.status = resolvedStatus
        internalAttempt.responsePayload = truncatePayload(objectMapper.writeValueAsString(mapOf("status" to request.status)))
        internalAttempt.updatedAt = Instant.now()
        internalAttemptRepository.save(internalAttempt)

        val shouldUpdate = resolvedStatus == InternalAttemptStatus.SUCCESS || resolvedStatus == InternalAttemptStatus.FAILURE

        return WebhookProcessResponse(
            type = internalAttempt.type.name.lowercase(),
            resolvedStatus = resolvedStatus.name.lowercase(),
            paymentAttemptId = internalAttempt.paymentAttemptId,
            shouldUpdate = shouldUpdate
        )
    }

    @Transactional
    fun expireAttempts(paymentAttemptIds: List<String>) {
        val now = Instant.now()
        for (attemptId in paymentAttemptIds) {
            val attempts = internalAttemptRepository.findByPaymentAttemptId(attemptId)
            for (ia in attempts.filter { it.status in setOf(InternalAttemptStatus.PENDING, InternalAttemptStatus.TIMEOUT) }) {
                ia.status = InternalAttemptStatus.EXPIRED
                ia.updatedAt = now
                internalAttemptRepository.save(ia)
                log.info("Expired InternalAttempt ${ia.id}")
            }
        }
    }

    fun getAttemptsByPaymentAttemptId(paymentAttemptId: String): List<InternalAttemptResponse> =
        internalAttemptRepository.findByPaymentAttemptId(paymentAttemptId).map { it.toResponse() }

    fun getAttemptsByPaymentAttemptIds(paymentAttemptIds: List<String>): Map<String, List<InternalAttemptResponse>> =
        if (paymentAttemptIds.isEmpty()) emptyMap()
        else internalAttemptRepository.findByPaymentAttemptIdIn(paymentAttemptIds)
            .map { it.toResponse() }
            .groupBy { it.paymentAttemptId }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDispatched(internalAttemptId: String) {
        val attempt = internalAttemptRepository.findById(internalAttemptId).orElse(null) ?: return
        attempt.dispatched = true
        attempt.updatedAt = Instant.now()
        internalAttemptRepository.save(attempt)
    }

    private fun dispatchCaptureAsync(internalAttempt: InternalAttempt) {
        try {
            gatewayClient.dispatchCapture(internalAttempt)
            markDispatched(internalAttempt.id)
        } catch (e: Exception) {
            log.warn("Capture dispatch failed for ${internalAttempt.id} — scheduler will retry: ${e.message}")
        }
    }

    private fun truncatePayload(payload: String?, maxLength: Int = 4096): String? =
        if (payload != null && payload.length > maxLength) payload.take(maxLength) else payload

    private fun InternalAttempt.toResponse() = InternalAttemptResponse(
        id = id, paymentAttemptId = paymentAttemptId,
        provider = provider.toApiString(), status = status.name.lowercase(),
        type = type.name.lowercase(), retryCount = retryCount,
        requestPayload = requestPayload, responsePayload = responsePayload,
        createdAt = createdAt, updatedAt = updatedAt
    )
}
