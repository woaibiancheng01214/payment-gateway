package com.payment.gateway.entity

enum class Currency {
    USD, EUR, GBP, JPY, AUD, CAD, CHF, CNY, HKD, SGD,
    NZD, SEK, NOK, DKK, KRW, INR, BRL, MXN, ZAR, THB,
    TWD, PLN, CZK, HUF, ILS, MYR, PHP, IDR, TRY, AED;

    companion object {
        fun fromString(value: String): Currency =
            try { valueOf(value.uppercase()) }
            catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unsupported currency: $value")
            }
    }
}
