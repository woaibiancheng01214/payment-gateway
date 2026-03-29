package com.payment.vault.service

import com.payment.vault.dto.CardDataResponse
import com.payment.vault.entity.CardData
import com.payment.vault.repository.CardDataRepository
import org.springframework.stereotype.Service

@Service
class VaultService(
    private val cardDataRepository: CardDataRepository,
    private val encryptionService: EncryptionService
) {
    fun createCardData(pan: String, expMonth: Int, expYear: Int, cardholderName: String?): String {
        val encryptedPan = encryptionService.encrypt(pan)
        val cardData = CardData(
            encryptedPan = encryptedPan,
            expMonth = expMonth,
            expYear = expYear,
            cardholderName = cardholderName
        )
        cardDataRepository.save(cardData)
        return cardData.id
    }

    fun getCardData(cardDataId: String): CardDataResponse {
        val cardData = cardDataRepository.findById(cardDataId)
            .orElseThrow { IllegalArgumentException("Card data not found: $cardDataId") }
        val decryptedPan = encryptionService.decrypt(cardData.encryptedPan)
        return CardDataResponse(
            pan = decryptedPan,
            expMonth = cardData.expMonth,
            expYear = cardData.expYear
        )
    }
}
