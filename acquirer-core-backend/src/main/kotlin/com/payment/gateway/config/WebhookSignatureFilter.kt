package com.payment.gateway.config

import com.payment.gateway.util.HmacUtils
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Wraps the request so the body can be read multiple times
 * (once for HMAC verification, once for @RequestBody deserialization).
 */
private class CachedBodyRequest(
    request: HttpServletRequest,
    val cachedBody: ByteArray
) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream {
        val bais = ByteArrayInputStream(cachedBody)
        return object : ServletInputStream() {
            override fun read(): Int = bais.read()
            override fun isFinished(): Boolean = bais.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener?) {}
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))
}

@Component
class WebhookSignatureFilter(
    @Value("\${gateway.webhook.secret:default-webhook-secret-change-me}") private val webhookSecret: String,
    @Value("\${gateway.webhook.secret.previous:}") private val previousWebhookSecret: String,
    @Value("\${gateway.webhook.tolerance-seconds:300}") private val toleranceSeconds: Long
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/v1/webhooks/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val bodyBytes = request.inputStream.readAllBytes()
        val cachedRequest = CachedBodyRequest(request, bodyBytes)

        val signature = cachedRequest.getHeader("X-Gateway-Signature")
        val timestamp = cachedRequest.getHeader("X-Gateway-Timestamp")

        if (signature == null || timestamp == null) {
            log.warn("Webhook rejected: missing signature or timestamp headers")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing webhook signature headers")
            return
        }

        val ts = timestamp.toLongOrNull()
        if (ts == null) {
            log.warn("Webhook rejected: non-numeric timestamp")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid timestamp")
            return
        }

        val now = System.currentTimeMillis() / 1000
        if (abs(now - ts) > toleranceSeconds) {
            log.warn("Webhook rejected: timestamp too old/far (delta=${abs(now - ts)}s, tolerance=${toleranceSeconds}s)")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Timestamp outside tolerance window")
            return
        }

        val bodyString = String(bodyBytes, Charsets.UTF_8)
        val signatureBytes = signature.toByteArray(Charsets.UTF_8)

        // Try current secret first, then previous secret during rotation
        val currentExpected = HmacUtils.hmacSha256(webhookSecret, "$timestamp.$bodyString")
        val matchesCurrent = MessageDigest.isEqual(signatureBytes, currentExpected.toByteArray(Charsets.UTF_8))

        val matchesPrevious = if (!matchesCurrent && previousWebhookSecret.isNotBlank()) {
            val previousExpected = HmacUtils.hmacSha256(previousWebhookSecret, "$timestamp.$bodyString")
            MessageDigest.isEqual(signatureBytes, previousExpected.toByteArray(Charsets.UTF_8))
        } else false

        if (!matchesCurrent && !matchesPrevious) {
            log.warn("Webhook rejected: signature mismatch")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid webhook signature")
            return
        }
        if (matchesPrevious) {
            log.info("Webhook verified with previous secret — rotation in progress")
        }

        filterChain.doFilter(cachedRequest, response)
    }
}
