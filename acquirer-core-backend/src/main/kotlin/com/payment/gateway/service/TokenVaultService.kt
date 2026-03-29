package com.payment.gateway.service

import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory token vault for PCI DSS scope reduction.
 *
 * Raw card data (PAN, CVC) is held only in this in-memory map — never persisted to the
 * database or written to logs. The rest of the system works exclusively with opaque tokens.
 *
 * In production this would be a separate, PCI-scoped vault service (e.g., Basis Theory,
 * Evervault, or an internal HSM-backed vault). The in-memory approach here models the
 * architecture correctly for an educational system.
 */
@Service
class TokenVaultService {

    private val vault = ConcurrentHashMap<String, CardData>()

    data class CardData(
        val cardNumber: String,
        val cardholderName: String?,
        val expiryMonth: Int,
        val expiryYear: Int,
        val cvc: String,
        val cardBrand: String,
        val last4: String
    )

    /**
     * Tokenizes raw card data from a CNP checkout form.
     * Returns an opaque token (tok_<uuid>). The raw PAN is stored only in memory.
     */
    fun tokenize(
        cardNumber: String,
        cardholderName: String?,
        expiryMonth: Int,
        expiryYear: Int,
        cvc: String
    ): String {
        val token = "tok_${UUID.randomUUID()}"
        val brand = deriveCardBrand(cardNumber)
        val last4 = cardNumber.takeLast(4)
        vault[token] = CardData(cardNumber, cardholderName, expiryMonth, expiryYear, cvc, brand, last4)
        return token
    }

    /** Resolves a token to its card brand for gateway behavior routing. */
    fun resolveCardBrand(token: String): String =
        vault[token]?.cardBrand ?: "unknown"

    /** Resolves the last 4 digits for safe display/logging. Never exposes the full PAN. */
    fun resolveLast4(token: String): String? =
        vault[token]?.last4

    /**
     * Derives the card brand from the BIN (first digits of the PAN).
     * In production this would use a full BIN table lookup.
     */
    private fun deriveCardBrand(cardNumber: String): String = when {
        cardNumber.startsWith("4") -> "visa"
        cardNumber.startsWith("51") || cardNumber.startsWith("52") ||
            cardNumber.startsWith("53") || cardNumber.startsWith("54") ||
            cardNumber.startsWith("55") -> "mastercard"
        cardNumber.startsWith("34") || cardNumber.startsWith("37") -> "amex"
        cardNumber.startsWith("6011") || cardNumber.startsWith("65") -> "discover"
        else -> "card_${cardNumber.takeLast(4)}"
    }
}
