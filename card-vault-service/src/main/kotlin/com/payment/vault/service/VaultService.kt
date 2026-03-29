package com.payment.vault.service

import com.payment.vault.dto.CardDataResponse
import com.payment.vault.entity.AuditLog
import com.payment.vault.entity.CardData
import com.payment.vault.repository.AuditLogRepository
import com.payment.vault.repository.CardDataRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Service
class VaultService(
    private val cardDataRepository: CardDataRepository,
    private val auditLogRepository: AuditLogRepository,
    private val encryptionService: EncryptionService
) {
    fun createCardData(pan: String, expMonth: Int, expYear: Int, cardholderName: String?): String {
        val encryptedPan = encryptionService.encrypt(pan)
        val cardData = CardData(
            encryptedPan = encryptedPan,
            expMonth = expMonth,
            expYear = expYear,
            cardholderName = cardholderName,
            keyVersion = encryptionService.currentKeyVersion()
        )
        cardDataRepository.save(cardData)
        return cardData.id
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun getCardData(cardDataId: String): CardDataResponse {
        val cardData = cardDataRepository.findById(cardDataId)
            .orElseThrow { IllegalArgumentException("Card data not found: $cardDataId") }
        val decryptedPan = encryptionService.decrypt(cardData.encryptedPan, cardData.keyVersion)

        // PCI audit: log every cardholder data decryption
        try {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            auditLogRepository.save(AuditLog(
                action = "DECRYPT",
                cardDataId = cardDataId,
                callerService = request?.getHeader("X-Correlation-Id"),
                callerIp = request?.remoteAddr
            ))
        } catch (e: Exception) {
            log.warn("Failed to write audit log for card_data $cardDataId: ${e.message}")
        }

        return CardDataResponse(
            pan = decryptedPan,
            expMonth = cardData.expMonth,
            expYear = cardData.expYear
        )
    }
}
