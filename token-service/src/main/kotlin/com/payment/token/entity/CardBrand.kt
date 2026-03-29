package com.payment.token.entity

enum class CardBrand {
    VISA, MASTERCARD, AMEX, DISCOVER, JCB, UNIONPAY, UNKNOWN;

    companion object {
        fun fromBin(cardNumber: String): CardBrand = when {
            cardNumber.startsWith("4") -> VISA
            cardNumber.startsWith("51") || cardNumber.startsWith("52") ||
                cardNumber.startsWith("53") || cardNumber.startsWith("54") ||
                cardNumber.startsWith("55") -> MASTERCARD
            cardNumber.startsWith("34") || cardNumber.startsWith("37") -> AMEX
            cardNumber.startsWith("6011") || cardNumber.startsWith("65") -> DISCOVER
            cardNumber.startsWith("35") -> JCB
            cardNumber.startsWith("62") -> UNIONPAY
            else -> UNKNOWN
        }
    }
}
