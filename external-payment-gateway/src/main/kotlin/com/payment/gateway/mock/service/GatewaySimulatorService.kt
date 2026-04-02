package com.payment.gateway.mock.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.gateway.mock.dto.WebhookPayload
import com.payment.gateway.mock.util.HmacUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class GatewayJobType { AUTH, CAPTURE }

@Service
class GatewaySimulatorService(
    private val objectMapper: ObjectMapper,
    @Value("\${gateway.delay.floor-ms:10000}") private val floorMs: Long,
    @Value("\${gateway.delay.cap-ms:120000}") private val capMs: Long,
    @Value("\${gateway.delay.lambda:0.000143}") private val lambda: Double,
    @Value("\${gateway.retry.max:2}") private val maxRetries: Int,
    @Value("\${gateway.auth.success-threshold:70}") private val authSuccessThreshold: Int,
    @Value("\${gateway.auth.failure-threshold:90}") private val authFailureThreshold: Int,
    @Value("\${gateway.capture.success-threshold:90}") private val captureSuccessThreshold: Int,
    @Value("\${gateway.capture.failure-threshold:95}") private val captureFailureThreshold: Int,
    @Value("\${gateway.webhook.secret:default-webhook-secret-change-me}") private val webhookSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newScheduledThreadPool(64)
    private val http = RestTemplate()

    /**
     * Dedup set — tracks attempts currently in the scheduler pipeline.
     * Allows re-dispatch once the scheduled task completes (fires webhook or exhausts retries).
     */
    private val inFlightAttempts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun simulate(
        type: GatewayJobType,
        internalAttemptId: String,
        cardBrand: String,
        callbackUrl: String,
        retriesLeft: Int = maxRetries,
        delayMs: Long = randomDelay()
    ) {
        if (!inFlightAttempts.add(internalAttemptId)) {
            log.debug("[$internalAttemptId] duplicate dispatch ignored — already in-flight")
            return
        }

        if (cardBrand.contains("card_hang")) {
            log.info("[$internalAttemptId] HANG simulated — no webhook will be fired")
            return
        }

        log.debug("[$internalAttemptId] scheduling $type outcome in ${delayMs}ms (retriesLeft=$retriesLeft)")
        scheduler.schedule({
            val outcome = rollOutcome(type)
            log.info("[$internalAttemptId] type=$type outcome=$outcome retriesLeft=$retriesLeft delayWas=${delayMs}ms")

            when (outcome) {
                "timeout" -> {
                    if (retriesLeft > 0) {
                        val backoffMs = 1000L * (1 shl (maxRetries - retriesLeft + 1))
                        log.info("[$internalAttemptId] timeout — retrying in ${backoffMs}ms (${retriesLeft - 1} retries left)")
                        inFlightAttempts.remove(internalAttemptId)
                        simulate(type, internalAttemptId, cardBrand, callbackUrl, retriesLeft - 1, backoffMs)
                    } else {
                        log.warn("[$internalAttemptId] max retries exhausted — delivering failure")
                        fireWebhook(callbackUrl, internalAttemptId, "failure")
                        inFlightAttempts.remove(internalAttemptId)
                    }
                }
                else -> {
                    fireWebhook(callbackUrl, internalAttemptId, outcome)
                    inFlightAttempts.remove(internalAttemptId)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun fireWebhook(callbackUrl: String, internalAttemptId: String, status: String) {
        try {
            val payload = WebhookPayload(internalAttemptId = internalAttemptId, status = status)
            val bodyJson = objectMapper.writeValueAsString(payload)
            val timestamp = Instant.now().epochSecond.toString()
            val signature = HmacUtils.hmacSha256(webhookSecret, "$timestamp.$bodyJson")

            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Gateway-Signature", signature)
                set("X-Gateway-Timestamp", timestamp)
            }
            val entity = HttpEntity(bodyJson, headers)
            val response: ResponseEntity<String> = http.postForEntity(callbackUrl, entity, String::class.java)
            log.info("[$internalAttemptId] webhook fired → $status (HTTP ${response.statusCode})")
        } catch (e: Exception) {
            log.error("[$internalAttemptId] failed to fire webhook to $callbackUrl: ${e.message}")
        }
    }

    fun rollOutcome(type: GatewayJobType): String {
        val roll = (1..100).random()
        return when (type) {
            GatewayJobType.AUTH -> when {
                roll <= authSuccessThreshold -> "success"
                roll <= authFailureThreshold -> "failure"
                else -> "timeout"
            }
            GatewayJobType.CAPTURE -> when {
                roll <= captureSuccessThreshold -> "success"
                roll <= captureFailureThreshold -> "failure"
                else -> "timeout"
            }
        }
    }

    /**
     * Exponential distribution with floor and cap:
     *   floor = 10s, cap = 120s, lambda ≈ 1/7000 → median ≈ 15s, P95 ≈ 31s, P99 ≈ 56s
     */
    private fun randomDelay(): Long {
        val u = Math.random()
        val sample = -Math.log(1.0 - u) / lambda
        return (floorMs + sample.toLong()).coerceAtMost(capMs)
    }
}
