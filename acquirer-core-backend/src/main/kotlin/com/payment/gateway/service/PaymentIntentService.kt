package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.gateway.dto.*
import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import com.payment.gateway.util.HashUtils
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class PaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val vaultClient: VaultClient,
    private val tokenClient: TokenClient,
    private val authClient: AuthClient,
    private val merchantClient: MerchantClient,
    private val lockManager: DistributedLockManager,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Business metrics ───────────────────────────────────────────────────
    private val createCounter = Counter.builder("payment.intents.created").register(meterRegistry)
    private val authoriseTimer = Timer.builder("payment.intents.authorise.duration")
        .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry)
    private val captureCounter = Counter.builder("payment.intents.capture.requested").register(meterRegistry)
    private val webhookCounter = Counter.builder("payment.webhooks.processed").register(meterRegistry)
    private fun statusChangeCounter(from: String, to: String) =
        Counter.builder("payment.intents.status_changes")
            .tag("from", from).tag("to", to).register(meterRegistry)

    // ── Create ──────────────────────────────────────────────────────────────

    fun createPaymentIntent(
        request: CreatePaymentIntentRequest,
        idempotencyKey: String?
    ): PaymentIntentResponse {
        val currency = request.validatedCurrency()

        // Validate merchant exists
        if (!merchantClient.merchantExists(request.merchantId)) {
            throw IllegalArgumentException("Merchant not found: ${request.merchantId}")
        }

        val requestHash = if (idempotencyKey != null) {
            HashUtils.sha256(
                "${request.merchantId}" +
                "${request.amount}${currency.name}" +
                "${request.description.orEmpty()}" +
                "${request.customerEmail.orEmpty()}" +
                "${request.customerId.orEmpty()}"
            )
        } else null

        // Redis pre-check via circuit breaker
        if (idempotencyKey != null && requestHash != null) {
            lockManager.redisGetIdem(idempotencyKey, requestHash)?.let {
                log.info("Redis HIT for idempotency key '$idempotencyKey' (create)")
                return objectMapper.readValue(it, PaymentIntentResponse::class.java)
            }
        }

        val response: PaymentIntentResponse = try {
            transactionTemplate.execute {
                if (idempotencyKey != null) {
                    val existing = idempotencyKeyRepository.findById(idempotencyKey)
                    if (existing.isPresent) {
                        if (existing.get().requestHash != requestHash) {
                            throw IllegalArgumentException(
                                "Idempotency key '$idempotencyKey' was already used with a different amount/currency"
                            )
                        }
                        log.info("DB HIT for idempotency key '$idempotencyKey' (create)")
                        return@execute objectMapper.readValue(existing.get().response, PaymentIntentResponse::class.java)
                    }
                }

                val intent = PaymentIntent(
                    merchantId = request.merchantId,
                    amount = request.amount,
                    currency = currency,
                    description = request.description,
                    statementDescriptor = request.statementDescriptor?.take(22),
                    metadata = request.metadata?.let { objectMapper.writeValueAsString(it) },
                    customerEmail = request.customerEmail,
                    customerId = request.customerId,
                    status = PaymentIntentStatus.REQUIRES_CONFIRMATION
                )
                paymentIntentRepository.save(intent)
                val resp = intent.toResponse()

                if (idempotencyKey != null) {
                    idempotencyKeyRepository.save(
                        IdempotencyKey(
                            key = idempotencyKey,
                            requestHash = requestHash!!,
                            response = objectMapper.writeValueAsString(resp)
                        )
                    )
                }
                resp
            }!!
        } catch (e: DataIntegrityViolationException) {
            if (idempotencyKey == null) throw e
            log.info("Concurrent create race on idempotency key '$idempotencyKey' — reading winner's response")
            val winner = idempotencyKeyRepository.findById(idempotencyKey)
                .orElseThrow { e }
            if (winner.requestHash != requestHash) {
                throw IllegalArgumentException(
                    "Idempotency key '$idempotencyKey' was already used with a different amount/currency"
                )
            }
            objectMapper.readValue(winner.response, PaymentIntentResponse::class.java)
        }

        // Cache in Redis via circuit breaker
        if (idempotencyKey != null && requestHash != null) {
            lockManager.redisSetIdem(idempotencyKey, requestHash, objectMapper.writeValueAsString(response))
        }
        createCounter.increment()
        return response
    }

    // ── Confirm ─────────────────────────────────────────────────────────────

    private data class ConfirmTxResult(
        val response: PaymentIntentResponse,
        val attemptId: String,
        val paymentMethodId: String,
        val amount: Long,
        val currency: String,
        val alreadyExisted: Boolean
    )

    fun authorisePaymentIntent(
        intentId: String,
        request: ConfirmPaymentIntentRequest
    ): PaymentIntentResponse {
        val sample = Timer.start()
        val lockResult = lockManager.acquireLock(intentId)

        try {
            // Tokenize via PCI services — raw PAN never persisted here
            val cardDataId = vaultClient.createCardData(
                pan = request.cardNumber,
                expMonth = request.expiryMonth,
                expYear = request.expiryYear,
                cardholderName = request.cardholderName
            )

            val brand = CardBrand.fromBin(request.cardNumber)
            val last4 = request.cardNumber.takeLast(4)

            val paymentMethodId = tokenClient.createPaymentMethod(
                customerId = null,
                cardDataId = cardDataId,
                brand = brand.name.lowercase(),
                last4 = last4,
                expMonth = request.expiryMonth,
                expYear = request.expiryYear
            )

            val txResult = transactionTemplate.execute {
                val intent = paymentIntentRepository.findByIdForUpdate(intentId)
                    .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

                if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
                    throw IllegalStateException("PaymentIntent is not in requires_confirmation state")
                }

                val existingAttempt = paymentAttemptRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(intentId)
                if (existingAttempt != null) {
                    log.warn("Duplicate authorise blocked for intent $intentId (attempt ${existingAttempt.id} already exists)")
                    return@execute ConfirmTxResult(
                        response = intent.toResponse(),
                        attemptId = existingAttempt.id,
                        paymentMethodId = paymentMethodId,
                        amount = intent.amount,
                        currency = intent.currency.name,
                        alreadyExisted = true
                    )
                }

                val attempt = PaymentAttempt(
                    paymentIntentId = intentId,
                    paymentMethodId = paymentMethodId,
                    cardBrand = brand,
                    last4 = last4,
                    status = PaymentAttemptStatus.PENDING
                )
                paymentAttemptRepository.save(attempt)

                ConfirmTxResult(
                    response = intent.toResponse(),
                    attemptId = attempt.id,
                    paymentMethodId = paymentMethodId,
                    amount = intent.amount,
                    currency = intent.currency.name,
                    alreadyExisted = false
                )
            }!!

            // Synchronous auth — gateway returns result inline
            if (!txResult.alreadyExisted) {
                try {
                    val authResult = authClient.authorise(intentId, txResult.paymentMethodId, txResult.attemptId, txResult.amount, txResult.currency)
                    markAttemptDispatched(txResult.attemptId)

                    if (authResult.status in listOf("success", "failure")) {
                        return applyAuthResult(intentId, txResult.attemptId, authResult.status)
                    }
                } catch (e: Exception) {
                    log.warn("Auth-service dispatch failed for intent $intentId — scheduler will retry: ${e.message}")
                }
            }

            return txResult.response
        } finally {
            lockManager.releaseLock(intentId, lockResult)
            sample.stop(authoriseTimer)
        }
    }

    fun applyAuthResult(intentId: String, attemptId: String, status: String): PaymentIntentResponse {
        return transactionTemplate.execute {
            val attempt = paymentAttemptRepository.findById(attemptId).orElseThrow()
            val intent = paymentIntentRepository.findByIdForUpdate(intentId).orElseThrow()

            if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
                return@execute intent.toResponse()
            }

            when (status) {
                "success" -> {
                    attempt.status = PaymentAttemptStatus.AUTHORIZED
                    intent.status = PaymentIntentStatus.AUTHORIZED
                    statusChangeCounter("requires_confirmation", "authorized").increment()
                    log.info("PaymentIntent $intentId authorized (sync)")
                }
                "failure" -> {
                    attempt.status = PaymentAttemptStatus.FAILED
                    intent.status = PaymentIntentStatus.FAILED
                    statusChangeCounter("requires_confirmation", "failed").increment()
                    log.info("PaymentIntent $intentId failed (sync)")
                }
            }
            attempt.updatedAt = Instant.now()
            intent.updatedAt = Instant.now()
            paymentAttemptRepository.save(attempt)
            paymentIntentRepository.save(intent)
            intent.toResponse()
        }!!
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    private data class CaptureTxResult(
        val response: PaymentIntentResponse,
        val attemptId: String,
        val amount: Long,
        val currency: String
    )

    fun capturePaymentIntent(intentId: String): PaymentIntentResponse {
        captureCounter.increment()
        val txResult = transactionTemplate.execute {
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

            // Fire-and-forget: mark as CAPTURED immediately
            intent.status = PaymentIntentStatus.CAPTURED
            intent.updatedAt = Instant.now()
            attempt.status = PaymentAttemptStatus.CAPTURED
            attempt.updatedAt = Instant.now()
            paymentIntentRepository.save(intent)
            paymentAttemptRepository.save(attempt)
            statusChangeCounter("authorized", "captured").increment()
            log.info("PaymentIntent $intentId captured (fire-and-forget)")

            CaptureTxResult(
                response = intent.toResponse(),
                attemptId = attempt.id,
                amount = intent.amount,
                currency = intent.currency.name
            )
        }!!

        // Dispatch capture to card-auth-service AFTER transaction commits (for settlement)
        try {
            authClient.capture(txResult.attemptId, txResult.amount, txResult.currency)
        } catch (e: Exception) {
            log.warn("Auth-service capture dispatch failed for intent $intentId — scheduler will retry: ${e.message}")
        }

        return txResult.response
    }

    // ── Webhook handling ────────────────────────────────────────────────────

    fun handleWebhook(internalAttemptId: String, gatewayStatus: String) {
        webhookCounter.increment()
        // Forward to auth-service to process InternalAttempt (outside transaction)
        val result = authClient.processWebhook(internalAttemptId, gatewayStatus)

        if (!result.shouldUpdate) {
            log.info("Webhook for $internalAttemptId — no update needed (status=${result.resolvedStatus})")
            return
        }

        // DB mutations inside transaction
        transactionTemplate.execute {
            val attempt = paymentAttemptRepository.findById(result.paymentAttemptId)
                .orElseThrow { IllegalStateException("PaymentAttempt ${result.paymentAttemptId} not found") }

            val intent = paymentIntentRepository.findByIdForUpdate(attempt.paymentIntentId)
                .orElseThrow { IllegalStateException("PaymentIntent not found") }

            when (result.type) {
                "auth" -> handleAuthWebhook(attempt, intent, result.resolvedStatus)
                "capture" -> handleCaptureWebhook(attempt, intent, result.resolvedStatus)
            }
        }
    }

    private fun handleAuthWebhook(attempt: PaymentAttempt, intent: PaymentIntent, resolvedStatus: String) {
        val terminalIntentStatuses = setOf(
            PaymentIntentStatus.AUTHORIZED, PaymentIntentStatus.CAPTURED,
            PaymentIntentStatus.FAILED, PaymentIntentStatus.EXPIRED
        )
        if (intent.status in terminalIntentStatuses) {
            log.warn("Ignoring auth webhook for already-terminal intent ${intent.id} (status=${intent.status})")
            return
        }
        when (resolvedStatus) {
            "success" -> {
                attempt.status = PaymentAttemptStatus.AUTHORIZED
                intent.status = PaymentIntentStatus.AUTHORIZED
                statusChangeCounter("requires_confirmation", "authorized").increment()
                log.info("PaymentIntent ${intent.id} authorized")
            }
            "failure" -> {
                attempt.status = PaymentAttemptStatus.FAILED
                intent.status = PaymentIntentStatus.FAILED
                statusChangeCounter("requires_confirmation", "failed").increment()
                log.info("PaymentIntent ${intent.id} failed")
            }
            else -> return
        }
        attempt.updatedAt = Instant.now()
        intent.updatedAt = Instant.now()
        paymentAttemptRepository.save(attempt)
        paymentIntentRepository.save(intent)
    }

    private fun handleCaptureWebhook(attempt: PaymentAttempt, intent: PaymentIntent, resolvedStatus: String) {
        // Intent is already CAPTURED (fire-and-forget). Webhook is for settlement confirmation only.
        when (resolvedStatus) {
            "success" -> log.info("Capture settlement confirmed for PaymentIntent ${intent.id}")
            "failure" -> log.warn("Capture settlement failed for PaymentIntent ${intent.id} — will be retried by scheduler")
            else -> log.info("Capture webhook with status $resolvedStatus for ${intent.id}")
        }
    }

    // ── Dispatch helpers ──────────────────────────────────────────────────

    fun markAttemptDispatched(attemptId: String) {
        transactionTemplate.execute {
            paymentAttemptRepository.markDispatched(attemptId, Instant.now())
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    fun getPaymentIntentSummary(intentId: String): PaymentIntentResponse {
        val intent = paymentIntentRepository.findById(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }
        return intent.toResponse()
    }

    fun getPaymentIntentDetail(intentId: String): PaymentIntentDetailResponse {
        val intent = paymentIntentRepository.findById(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

        val attempts = paymentAttemptRepository.findByPaymentIntentId(intentId)
        val attemptIds = attempts.map { it.id }

        // Fetch InternalAttempts from auth-service
        val allInternalAttempts = authClient.getAttemptsBatch(attemptIds)

        val attemptDetails = attempts.map { attempt ->
            val internalAttempts = allInternalAttempts[attempt.id] ?: emptyList()
            PaymentAttemptDetailResponse(
                id = attempt.id,
                paymentIntentId = attempt.paymentIntentId,
                paymentMethodId = attempt.paymentMethodId,
                cardBrand = attempt.cardBrand?.name?.lowercase(),
                last4 = attempt.last4,
                status = attempt.status.name.lowercase(),
                createdAt = attempt.createdAt,
                updatedAt = attempt.updatedAt,
                internalAttempts = internalAttempts.map { ia ->
                    com.payment.gateway.dto.InternalAttemptResponse(
                        id = ia.id, paymentAttemptId = ia.paymentAttemptId,
                        provider = ia.provider, status = ia.status,
                        type = ia.type, retryCount = ia.retryCount,
                        requestPayload = ia.requestPayload, responsePayload = ia.responsePayload,
                        createdAt = ia.createdAt, updatedAt = ia.updatedAt
                    )
                }
            )
        }

        return intent.toDetailResponse(attemptDetails)
    }

    fun listPaymentIntents(pageable: Pageable): Page<PaymentIntentResponse> =
        paymentIntentRepository.findAll(pageable).map { it.toResponse() }

    fun listPaymentIntentsByMerchant(merchantId: String, pageable: Pageable): Page<PaymentIntentResponse> {
        if (!merchantClient.merchantExists(merchantId)) {
            throw IllegalArgumentException("Merchant not found: $merchantId")
        }
        return paymentIntentRepository.findByMerchantId(merchantId, pageable).map { it.toResponse() }
    }

    // ── Cursor-based pagination ────────────────────────────────────────────

    fun listPaymentIntentsCursor(startingAfter: String?, limit: Int): CursorPageResponse {
        val pageSize = limit.coerceIn(1, 100)
        val pageable = Pageable.ofSize(pageSize)

        val intents = if (startingAfter != null) {
            val cursor = paymentIntentRepository.findById(startingAfter)
                .orElseThrow { IllegalArgumentException("Cursor intent not found: $startingAfter") }
            paymentIntentRepository.findWithCursor(cursor.createdAt, cursor.id, pageable)
        } else {
            paymentIntentRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
        }

        val data = intents.map { it.toResponse() }
        val hasMore = data.size == pageSize
        return CursorPageResponse(data = data, hasMore = hasMore)
    }

    fun listPaymentIntentsByMerchantCursor(merchantId: String, startingAfter: String?, limit: Int): CursorPageResponse {
        if (!merchantClient.merchantExists(merchantId)) {
            throw IllegalArgumentException("Merchant not found: $merchantId")
        }
        val pageSize = limit.coerceIn(1, 100)
        val pageable = Pageable.ofSize(pageSize)

        val intents = if (startingAfter != null) {
            val cursor = paymentIntentRepository.findById(startingAfter)
                .orElseThrow { IllegalArgumentException("Cursor intent not found: $startingAfter") }
            paymentIntentRepository.findByMerchantIdWithCursor(merchantId, cursor.createdAt, cursor.id, pageable)
        } else {
            paymentIntentRepository.findByMerchantIdOrdered(merchantId, pageable)
        }

        val data = intents.map { it.toResponse() }
        val hasMore = data.size == pageSize
        return CursorPageResponse(data = data, hasMore = hasMore)
    }

    data class CursorPageResponse(
        val data: List<PaymentIntentResponse>,
        val hasMore: Boolean
    )
}
