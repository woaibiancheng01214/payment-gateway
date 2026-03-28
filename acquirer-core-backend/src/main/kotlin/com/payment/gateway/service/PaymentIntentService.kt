package com.payment.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.gateway.dto.*
import com.payment.gateway.entity.*
import com.payment.gateway.repository.*
import com.payment.gateway.util.HashUtils
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class PaymentIntentService(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val internalAttemptRepository: InternalAttemptRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val gatewayClient: GatewayClient,
    private val dispatchMarkService: DispatchMarkService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val IDEM_PREFIX = "idempotency:"
        private const val LOCK_PREFIX = "lock:intent:"
        private val IDEM_TTL = Duration.ofHours(24)
        private val LOCK_TTL = Duration.ofSeconds(30)
    }

    // ── Redis idempotency cache (for create) ────────────────────────────────

    /**
     * Redis cache entry format: "requestHash\nresponseJson".
     * The hash is stored alongside the response so a Redis hit can detect payload
     * mismatches without falling through to the DB.
     */
    private fun redisGetIdem(key: String, requestHash: String): String? {
        val raw = try { redisTemplate.opsForValue().get(IDEM_PREFIX + key) }
                  catch (e: Exception) { log.warn("Redis GET failed, falling through to DB: ${e.message}"); return null }
            ?: return null
        val sep = raw.indexOf('\n')
        if (sep < 0) return raw
        val cachedHash = raw.substring(0, sep)
        val cachedResponse = raw.substring(sep + 1)
        if (cachedHash != requestHash) {
            throw IllegalArgumentException(
                "Idempotency key was already used with a different amount/currency"
            )
        }
        return cachedResponse
    }

    private fun redisSetIdem(key: String, requestHash: String, responseJson: String) =
        try { redisTemplate.opsForValue().set(IDEM_PREFIX + key, "$requestHash\n$responseJson", IDEM_TTL) }
        catch (e: Exception) { log.warn("Redis SET failed for idempotency key '$key': ${e.message}") }

    // ── Redis distributed lock (for confirm/capture) ────────────────────────

    /**
     * Acquires a Redis distributed lock using SET NX EX.
     * Returns the lock value (UUID) on success, null if the lock is already held.
     */
    private fun acquireRedisLock(intentId: String): String? {
        val lockValue = UUID.randomUUID().toString()
        val acquired = try {
            redisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + intentId, lockValue, LOCK_TTL)
                ?: false
        } catch (e: Exception) {
            log.warn("Redis lock acquire failed for intent $intentId, proceeding without lock: ${e.message}")
            return lockValue
        }
        return if (acquired) lockValue else null
    }

    /**
     * Releases a Redis distributed lock only if it's still owned by this holder.
     * Uses a Lua script for atomic check-and-delete to avoid releasing another holder's lock.
     */
    private fun releaseRedisLock(intentId: String, lockValue: String) {
        try {
            val script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
            redisTemplate.execute(
                org.springframework.data.redis.core.script.DefaultRedisScript(script, Long::class.java),
                listOf(LOCK_PREFIX + intentId),
                lockValue
            )
        } catch (e: Exception) {
            log.warn("Redis lock release failed for intent $intentId: ${e.message}")
        }
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Creates a PaymentIntent with optional idempotency key.
     *
     * Flow:
     * 1. Redis pre-check: if the key is cached, return immediately (zero DB cost).
     * 2. On cache miss: insert IdempotencyKey row optimistically (unique constraint on PK).
     *    - If insert succeeds → this is the first request, create the PaymentIntent normally.
     *    - If DataIntegrityViolationException → a concurrent request won the race,
     *      read the winner's response from the IdempotencyKey table and return it.
     * 3. After commit, write the response to Redis so subsequent retries hit the cache.
     *
     * No advisory locks, no distributed locks — the DB unique constraint serialises the race.
     */
    fun createPaymentIntent(
        request: CreatePaymentIntentRequest,
        idempotencyKey: String?
    ): PaymentIntentResponse {
        val requestHash = if (idempotencyKey != null) {
            HashUtils.sha256("${request.amount}${request.currency.uppercase()}")
        } else null

        if (idempotencyKey != null && requestHash != null) {
            redisGetIdem(idempotencyKey, requestHash)?.let {
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
                    amount = request.amount,
                    currency = request.currency.uppercase(),
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

        if (idempotencyKey != null && requestHash != null) {
            redisSetIdem(idempotencyKey, requestHash, objectMapper.writeValueAsString(response))
        }
        return response
    }

    // ── Confirm ─────────────────────────────────────────────────────────────

    /**
     * Confirms a PaymentIntent, creating a PaymentAttempt + auth InternalAttempt.
     *
     * Concurrency is controlled by a Redis distributed lock on the intentId:
     * - SET NX EX ensures only one confirm is processed at a time across all app instances.
     * - If the lock is already held, return 409 immediately (no DB connection consumed).
     * - findByIdForUpdate remains as a DB-level safety net inside the transaction.
     *
     * No idempotency key needed — the lock is on the resource (intentId) itself.
     */
    fun confirmPaymentIntent(
        intentId: String,
        request: ConfirmPaymentIntentRequest
    ): PaymentIntentResponse {
        val lockValue = acquireRedisLock(intentId)
            ?: throw IllegalStateException("Another confirm is already in progress for PaymentIntent $intentId")

        try {
            val (response, attemptToDispatch) = transactionTemplate.execute {
                val intent = paymentIntentRepository.findByIdForUpdate(intentId)
                    .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

                if (intent.status != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
                    throw IllegalStateException("PaymentIntent is not in requires_confirmation state")
                }

                val existingAttempt = paymentAttemptRepository.findTopByPaymentIntentIdOrderByCreatedAtDesc(intentId)
                if (existingAttempt != null) {
                    log.warn("Duplicate confirm blocked for intent $intentId (attempt ${existingAttempt.id} already exists)")
                    return@execute Pair(intent.toResponse(), null)
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

                Pair(intent.toResponse(), internalAttempt as InternalAttempt?)
            }!!

            dispatchBestEffort(attemptToDispatch)
            return response
        } finally {
            releaseRedisLock(intentId, lockValue)
        }
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    fun capturePaymentIntent(intentId: String): PaymentIntentResponse {
        val (response, attemptToDispatch) = transactionTemplate.execute {
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

            val alreadyCapturing = internalAttemptRepository.findByPaymentAttemptId(attempt.id)
                .any { it.type == InternalAttemptType.CAPTURE }
            if (alreadyCapturing) {
                log.warn("Duplicate capture blocked for intent $intentId")
                return@execute Pair(intent.toResponse(), null)
            }

            val internalAttempt = InternalAttempt(
                paymentAttemptId = attempt.id,
                type = InternalAttemptType.CAPTURE,
                requestPayload = objectMapper.writeValueAsString(
                    mapOf("paymentAttemptId" to attempt.id, "action" to "capture")
                )
            )
            internalAttemptRepository.save(internalAttempt)

            Pair(intent.toResponse(), internalAttempt as InternalAttempt?)
        }!!

        dispatchBestEffort(attemptToDispatch)
        return response
    }

    // ── Gateway dispatch ────────────────────────────────────────────────────

    private fun dispatchBestEffort(internalAttempt: InternalAttempt?) {
        if (internalAttempt == null) return
        try {
            gatewayClient.dispatch(internalAttempt)
            dispatchMarkService.markDispatched(internalAttempt.id)
        } catch (e: Exception) {
            log.warn("Dispatch failed for ${internalAttempt.id} — scheduler will retry: ${e.message}")
        }
    }

    // ── Webhook handling ────────────────────────────────────────────────────

    @Transactional
    fun handleWebhook(internalAttemptId: String, gatewayStatus: String) {
        val probe = internalAttemptRepository.findById(internalAttemptId)
            .orElseThrow { IllegalArgumentException("InternalAttempt $internalAttemptId not found") }
        val probedAttempt = paymentAttemptRepository.findById(probe.paymentAttemptId)
            .orElseThrow { IllegalStateException("PaymentAttempt not found for InternalAttempt $internalAttemptId") }

        val intent = paymentIntentRepository.findByIdForUpdate(probedAttempt.paymentIntentId)
            .orElseThrow { IllegalStateException("PaymentIntent not found") }

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
                log.warn("Capture failed for PaymentIntent ${intent.id} — still authorized, retry is possible")
            }
            else -> {
                log.info("Capture webhook with status $status for ${intent.id} — retry pending")
            }
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    fun getPaymentIntentDetail(intentId: String): PaymentIntentDetailResponse {
        val intent = paymentIntentRepository.findById(intentId)
            .orElseThrow { IllegalArgumentException("PaymentIntent $intentId not found") }

        val attempts = paymentAttemptRepository.findByPaymentIntentId(intentId)
        val attemptIds = attempts.map { it.id }
        val allInternalAttempts = if (attemptIds.isNotEmpty()) {
            internalAttemptRepository.findByPaymentAttemptIdIn(attemptIds).groupBy { it.paymentAttemptId }
        } else emptyMap()

        val attemptDetails = attempts.map { attempt ->
            PaymentAttemptDetailResponse(
                id = attempt.id,
                paymentIntentId = attempt.paymentIntentId,
                paymentMethod = attempt.paymentMethod,
                status = attempt.status.name.lowercase(),
                createdAt = attempt.createdAt,
                updatedAt = attempt.updatedAt,
                internalAttempts = (allInternalAttempts[attempt.id] ?: emptyList()).map { it.toResponse() }
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

    fun listPaymentIntents(pageable: Pageable): Page<PaymentIntentResponse> =
        paymentIntentRepository.findAll(pageable).map { it.toResponse() }
}
