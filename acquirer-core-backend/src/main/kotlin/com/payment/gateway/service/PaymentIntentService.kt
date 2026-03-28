package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.gateway.dto.*
import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors

@Service
class PaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val internalAttemptRepository: InternalAttemptRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val gatewayClient: GatewayClient,
    private val dispatchMarkService: DispatchMarkService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dispatchExecutor = Executors.newFixedThreadPool(32)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * Acquires a PostgreSQL transaction-level advisory lock keyed on the idempotency key string.
     *
     * pg_advisory_xact_lock blocks until no other transaction holds the same lock, then grants
     * it exclusively. The lock is automatically released when the transaction commits or rolls
     * back — no manual cleanup required. Because the lock lives inside PostgreSQL (not the JVM),
     * it works across every application instance connected to the same database.
     *
     * Lock ID is derived from the first 8 bytes of SHA-256(key), giving a 64-bit integer with
     * negligible collision probability (~1 in 2^64).
     */
    private fun acquireIdempotencyLock(key: String) {
        val lockId = ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        ).long
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:id)")
            .setParameter("id", lockId)
            .singleResult
        log.debug("Advisory lock acquired for idempotency key '$key' (lockId=$lockId)")
    }

    @Transactional
    fun createPaymentIntent(
        request: CreatePaymentIntentRequest,
        idempotencyKey: String?
    ): PaymentIntentResponse {
        if (idempotencyKey != null) {
            // Acquire the advisory lock FIRST so that concurrent requests with the same key
            // are serialized at the database level across all application instances.
            // Only after this lock is granted do we check-and-write — eliminating the
            // check-then-act race entirely.
            acquireIdempotencyLock(idempotencyKey)

            val requestHash = hashRequest("${request.amount}${request.currency.uppercase()}")
            val existing = idempotencyKeyRepository.findById(idempotencyKey)
            if (existing.isPresent) {
                if (existing.get().requestHash != requestHash) {
                    throw IllegalArgumentException(
                        "Idempotency key '$idempotencyKey' was already used with a different amount/currency"
                    )
                }
                log.info("Returning cached create response for idempotency key $idempotencyKey")
                return objectMapper.readValue(existing.get().response, PaymentIntentResponse::class.java)
            }
        }

        val intent = PaymentIntent(
            amount = request.amount,
            currency = request.currency.uppercase(),
            status = PaymentIntentStatus.REQUIRES_CONFIRMATION
        )
        paymentIntentRepository.save(intent)
        val response = intent.toResponse()

        if (idempotencyKey != null) {
            val requestHash = hashRequest("${request.amount}${request.currency.uppercase()}")
            idempotencyKeyRepository.save(
                IdempotencyKey(
                    key = idempotencyKey,
                    requestHash = requestHash,
                    response = objectMapper.writeValueAsString(response)
                )
            )
        }
        return response
    }

    @Transactional
    fun confirmPaymentIntent(
        intentId: String,
        request: ConfirmPaymentIntentRequest,
        idempotencyKey: String?
    ): PaymentIntentResponse {
        val intent = paymentIntentRepository.findByIdForUpdate(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

        if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
            throw IllegalStateException("PaymentIntent is not in requires_confirmation state")
        }

        if (idempotencyKey != null) {
            val requestHash = hashRequest(intentId + request.paymentMethod)
            val existing = idempotencyKeyRepository.findById(idempotencyKey)
            if (existing.isPresent) {
                if (existing.get().requestHash != requestHash) {
                    throw IllegalArgumentException("Idempotency key reused with different request payload")
                }
                log.info("Returning cached idempotency response for key $idempotencyKey")
                return objectMapper.readValue(existing.get().response, PaymentIntentResponse::class.java)
            }
        }

        // Guard against duplicate confirms racing past the status check
        val existingAttempt = paymentAttemptRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(intentId)
        if (existingAttempt != null) {
            log.warn("Duplicate confirm blocked for intent $intentId (attempt ${existingAttempt.id} already exists)")
            return intent.toResponse()
        }

        val attempt = PaymentAttempt(
            paymentIntentId = intentId,
            paymentMethod = request.paymentMethod,
            status = PaymentAttemptStatus.PENDING
        )
        paymentAttemptRepository.save(attempt)

        val internalAttempt = InternalAttempt(
            paymentAttemptId = attempt.id,
            type = InternalAttemptType.AUTH,
            requestPayload = objectMapper.writeValueAsString(
                mapOf("paymentMethod" to request.paymentMethod, "amount" to intent.amount, "currency" to intent.currency)
            )
        )
        internalAttemptRepository.save(internalAttempt)

        val response = intent.toResponse()

        if (idempotencyKey != null) {
            val requestHash = hashRequest(intentId + request.paymentMethod)
            idempotencyKeyRepository.save(
                IdempotencyKey(
                    key = idempotencyKey,
                    requestHash = requestHash,
                    response = objectMapper.writeValueAsString(response)
                )
            )
        }

        scheduleDispatchAfterCommit(internalAttempt)
        return response
    }

    @Transactional
    fun capturePaymentIntent(intentId: String): PaymentIntentResponse {
        val intent = paymentIntentRepository.findByIdForUpdate(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

        if (intent.status == PaymentIntentStatus.EXPIRED) {
            throw IllegalStateException("PaymentIntent has expired — cannot capture")
        }
        if (intent.status != PaymentIntentStatus.AUTHORIZED) {
            throw IllegalStateException("PaymentIntent must be in authorized state to capture (current: ${intent.status})")
        }

        val attempt = paymentAttemptRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(intentId)
            ?: throw IllegalStateException("No PaymentAttempt found for intent $intentId")

        // Guard against concurrent captures racing past the status check
        val alreadyCapturing = internalAttemptRepository.findByPaymentAttemptId(attempt.id)
            .any { it.type == InternalAttemptType.CAPTURE }
        if (alreadyCapturing) {
            log.warn("Duplicate capture blocked for intent $intentId")
            return intent.toResponse()
        }

        val internalAttempt = InternalAttempt(
            paymentAttemptId = attempt.id,
            type = InternalAttemptType.CAPTURE,
            requestPayload = objectMapper.writeValueAsString(
                mapOf("paymentAttemptId" to attempt.id, "action" to "capture")
            )
        )
        internalAttemptRepository.save(internalAttempt)

        scheduleDispatchAfterCommit(internalAttempt)
        return intent.toResponse()
    }

    /**
     * Registers a post-commit callback that dispatches the InternalAttempt to the gateway
     * AFTER the DB transaction has successfully committed. If the dispatch fails (network
     * timeout, gateway down), the attempt stays dispatched=false and the scheduler will
     * retry it within a few seconds.
     */
    private fun scheduleDispatchAfterCommit(internalAttempt: InternalAttempt) {
        val attemptId = internalAttempt.id
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                dispatchExecutor.submit {
                    try {
                        gatewayClient.dispatch(internalAttempt)
                        dispatchMarkService.markDispatched(attemptId)
                    } catch (e: Exception) {
                        log.warn("Post-commit dispatch failed for $attemptId — scheduler will retry: ${e.message}")
                    }
                }
            }
        })
    }

    @Transactional
    fun handleWebhook(internalAttemptId: String, gatewayStatus: String) {
        // --- Phase 1: navigate to the PaymentIntent ID without any lock ---
        // We read InternalAttempt and PaymentAttempt here only to discover the
        // paymentIntentId; actual business logic happens after we hold the lock.
        val probe = internalAttemptRepository.findById(internalAttemptId)
            .orElseThrow { IllegalArgumentException("InternalAttempt $internalAttemptId not found") }
        val probedAttempt = paymentAttemptRepository.findById(probe.paymentAttemptId)
            .orElseThrow { IllegalStateException("PaymentAttempt not found for InternalAttempt $internalAttemptId") }

        // --- Phase 2: acquire the aggregate-root write lock (same order as confirm/capture) ---
        // All writers (confirm, capture, webhook, expiry scheduler) must hold this lock before
        // mutating any entity in this intent's tree, preventing refund/cancel from racing against
        // an in-flight capture or auth webhook.
        val intent = paymentIntentRepository.findByIdForUpdate(probedAttempt.paymentIntentId)
            .orElseThrow { IllegalStateException("PaymentIntent not found") }

        // --- Phase 3: re-read InternalAttempt under the lock ---
        // The expiry scheduler runs in REQUIRES_NEW transactions and may have marked this attempt
        // EXPIRED between our initial read (phase 1) and lock acquisition (phase 2).
        val internalAttempt = internalAttemptRepository.findById(internalAttemptId)
            .orElseThrow { IllegalArgumentException("InternalAttempt $internalAttemptId not found") }

        val terminalInternalStatuses = setOf(
            InternalAttemptStatus.SUCCESS,
            InternalAttemptStatus.FAILURE,
            InternalAttemptStatus.EXPIRED
        )
        if (internalAttempt.status in terminalInternalStatuses) {
            log.warn("Ignoring webhook for already-terminal InternalAttempt $internalAttemptId (status=${internalAttempt.status})")
            return
        }

        val resolvedStatus = when (gatewayStatus.lowercase()) {
            "success" -> InternalAttemptStatus.SUCCESS
            "failure" -> InternalAttemptStatus.FAILURE
            "timeout" -> InternalAttemptStatus.TIMEOUT
            else -> throw IllegalArgumentException("Unknown gateway status: $gatewayStatus")
        }

        internalAttempt.status = resolvedStatus
        internalAttempt.responsePayload = objectMapper.writeValueAsString(mapOf("status" to gatewayStatus))
        internalAttempt.updatedAt = Instant.now()
        internalAttemptRepository.save(internalAttempt)

        val attempt = paymentAttemptRepository.findById(internalAttempt.paymentAttemptId)
            .orElseThrow { IllegalStateException("PaymentAttempt not found") }

        when (internalAttempt.type) {
            InternalAttemptType.AUTH -> handleAuthWebhook(attempt, intent, resolvedStatus)
            InternalAttemptType.CAPTURE -> handleCaptureWebhook(attempt, intent, resolvedStatus)
        }
    }

    private fun handleAuthWebhook(
        attempt: PaymentAttempt,
        intent: PaymentIntent,
        status: InternalAttemptStatus
    ) {
        val terminalIntentStatuses = setOf(
            PaymentIntentStatus.AUTHORIZED,
            PaymentIntentStatus.CAPTURED,
            PaymentIntentStatus.FAILED,
            PaymentIntentStatus.EXPIRED
        )
        if (intent.status in terminalIntentStatuses) {
            log.warn("Ignoring auth webhook for already-terminal intent ${intent.id} (status=${intent.status})")
            return
        }
        when (status) {
            InternalAttemptStatus.SUCCESS -> {
                attempt.status = PaymentAttemptStatus.AUTHORIZED
                intent.status = PaymentIntentStatus.AUTHORIZED
                log.info("PaymentIntent ${intent.id} authorized")
            }
            InternalAttemptStatus.FAILURE -> {
                attempt.status = PaymentAttemptStatus.FAILED
                intent.status = PaymentIntentStatus.FAILED
                log.info("PaymentIntent ${intent.id} failed")
            }
            else -> {
                log.info("Auth webhook with status $status - no final state update yet (retry pending)")
                return
            }
        }
        attempt.updatedAt = Instant.now()
        intent.updatedAt = Instant.now()
        paymentAttemptRepository.save(attempt)
        paymentIntentRepository.save(intent)
    }

    private fun handleCaptureWebhook(
        attempt: PaymentAttempt,
        intent: PaymentIntent,
        status: InternalAttemptStatus
    ) {
        when (status) {
            InternalAttemptStatus.SUCCESS -> {
                attempt.status = PaymentAttemptStatus.CAPTURED
                intent.status = PaymentIntentStatus.CAPTURED
                attempt.updatedAt = Instant.now()
                intent.updatedAt = Instant.now()
                paymentAttemptRepository.save(attempt)
                paymentIntentRepository.save(intent)
                log.info("PaymentIntent ${intent.id} captured")
            }
            InternalAttemptStatus.FAILURE -> {
                // Capture failed — intent stays AUTHORIZED so the merchant can retry capture.
                // No state change; the InternalAttempt already records the failure above.
                log.warn("Capture failed for PaymentIntent ${intent.id} — still authorized, retry is possible")
            }
            else -> {
                // TIMEOUT handled by retry logic in MockGatewayService
                log.info("Capture webhook with status $status for ${intent.id} — retry pending")
            }
        }
    }

    fun getPaymentIntentDetail(intentId: String): PaymentIntentDetailResponse {
        val intent = paymentIntentRepository.findById(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

        val attempts = paymentAttemptRepository.findByPaymentIntentId(intentId)
        val attemptDetails = attempts.map { attempt ->
            val internalAttempts = internalAttemptRepository.findByPaymentAttemptId(attempt.id)
            PaymentAttemptDetailResponse(
                id = attempt.id,
                paymentIntentId = attempt.paymentIntentId,
                paymentMethod = attempt.paymentMethod,
                status = attempt.status.name.lowercase(),
                createdAt = attempt.createdAt,
                updatedAt = attempt.updatedAt,
                internalAttempts = internalAttempts.map { it.toResponse() }
            )
        }

        return PaymentIntentDetailResponse(
            id = intent.id,
            amount = intent.amount,
            currency = intent.currency,
            status = intent.status.name.lowercase(),
            createdAt = intent.createdAt,
            updatedAt = intent.updatedAt,
            attempts = attemptDetails
        )
    }

    fun listPaymentIntents(): List<PaymentIntentResponse> =
        paymentIntentRepository.findAll().map { it.toResponse() }

    private fun hashRequest(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
