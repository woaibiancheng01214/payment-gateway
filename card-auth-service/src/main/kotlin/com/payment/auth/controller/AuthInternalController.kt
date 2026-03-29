package com.payment.auth.controller

import com.payment.auth.dto.*
import com.payment.auth.service.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/internal/auth")
class AuthInternalController(
    private val authService: AuthService
) {
    @PostMapping("/confirm")
    fun confirm(@RequestBody request: ConfirmRequest): ResponseEntity<ConfirmResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.confirm(request))

    @PostMapping("/capture")
    fun capture(@RequestBody request: CaptureRequest): ResponseEntity<CaptureResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.capture(request))

    @PostMapping("/webhook")
    fun processWebhook(@RequestBody request: WebhookProcessRequest): ResponseEntity<WebhookProcessResponse> =
        ResponseEntity.ok(authService.processWebhook(request))

    @PostMapping("/expire")
    fun expire(@RequestBody request: ExpireRequest): ResponseEntity<Map<String, String>> {
        authService.expireAttempts(request.paymentAttemptIds)
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    @GetMapping("/attempts/{paymentAttemptId}")
    fun getAttempts(@PathVariable paymentAttemptId: String): ResponseEntity<List<InternalAttemptResponse>> =
        ResponseEntity.ok(authService.getAttemptsByPaymentAttemptId(paymentAttemptId))

    @PostMapping("/attempts/batch")
    fun getAttemptsBatch(@RequestBody paymentAttemptIds: List<String>): ResponseEntity<Map<String, List<InternalAttemptResponse>>> =
        ResponseEntity.ok(authService.getAttemptsByPaymentAttemptIds(paymentAttemptIds))
}
