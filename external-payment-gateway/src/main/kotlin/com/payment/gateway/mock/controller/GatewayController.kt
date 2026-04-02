package com.payment.gateway.mock.controller

import com.payment.gateway.mock.dto.AuthorizeRequest
import com.payment.gateway.mock.dto.AuthorizeResponse
import com.payment.gateway.mock.dto.CaptureRequest
import com.payment.gateway.mock.dto.GatewayAckResponse
import com.payment.gateway.mock.service.GatewayJobType
import com.payment.gateway.mock.service.GatewaySimulatorService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1")
class GatewayController(
    private val simulator: GatewaySimulatorService,
    @Value("\${gateway.ack.floor-ms:10}") private val ackFloorMs: Long,
    @Value("\${gateway.ack.cap-ms:5000}") private val ackCapMs: Long,
    @Value("\${gateway.ack.lambda:0.002}") private val ackLambda: Double,
    @Value("\${gateway.sync-auth.floor-ms:50}") private val syncAuthFloorMs: Long,
    @Value("\${gateway.sync-auth.cap-ms:300}") private val syncAuthCapMs: Long,
    @Value("\${gateway.sync-auth.lambda:0.005}") private val syncAuthLambda: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/authorize")
    fun authorize(@RequestBody req: AuthorizeRequest): ResponseEntity<AuthorizeResponse> {
        log.info("Authorize received: attemptId=${req.internalAttemptId} token=${req.paymentToken} brand=${req.cardBrand} amount=${req.amount} ${req.currency}")
        simulateSyncAuthLatency(req.internalAttemptId)
        val outcome = simulator.rollOutcome(GatewayJobType.AUTH)
        val status = if (outcome == "timeout") "failure" else outcome
        log.info("[${req.internalAttemptId}] sync auth result: $status")
        return ResponseEntity.ok(AuthorizeResponse(gatewayRef = req.internalAttemptId, status = status))
    }

    @PostMapping("/capture")
    fun capture(@RequestBody req: CaptureRequest): ResponseEntity<GatewayAckResponse> {
        log.info("Capture received: attemptId=${req.internalAttemptId}")
        simulateAckLatency(req.internalAttemptId)
        simulator.simulate(
            type = GatewayJobType.CAPTURE,
            internalAttemptId = req.internalAttemptId,
            cardBrand = "",
            callbackUrl = req.callbackUrl
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(GatewayAckResponse(gatewayRef = req.internalAttemptId))
    }

    /**
     * Exponential ACK latency: floor 10ms, cap 5000ms.
     * Used for async endpoints (capture).
     */
    private fun simulateAckLatency(attemptId: String) {
        val u = Math.random()
        val sample = -Math.log(1.0 - u) / ackLambda
        val delayMs = (ackFloorMs + sample.toLong()).coerceAtMost(ackCapMs)
        if (delayMs > 1000) log.info("[$attemptId] simulating slow ACK: ${delayMs}ms")
        Thread.sleep(delayMs)
    }

    /**
     * Synchronous auth latency: floor 50ms, cap 300ms.
     * Simulates a real gateway processing an auth decision inline.
     */
    private fun simulateSyncAuthLatency(attemptId: String) {
        val u = Math.random()
        val sample = -Math.log(1.0 - u) / syncAuthLambda
        val delayMs = (syncAuthFloorMs + sample.toLong()).coerceAtMost(syncAuthCapMs)
        Thread.sleep(delayMs)
    }

    @ExceptionHandler(Exception::class)
    fun handleError(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to (e.message ?: "Internal error")))
}
