package com.payment.gateway.entity

enum class Provider {
    MOCK_VISA,
    MOCK_MASTERCARD,
    MOCK_GENERIC;

    fun toApiString(): String = name.lowercase().replace('_', '-')

    companion object {
        fun fromString(value: String): Provider =
            try { valueOf(value.uppercase().replace('-', '_')) }
            catch (e: IllegalArgumentException) { MOCK_GENERIC }
    }
}
