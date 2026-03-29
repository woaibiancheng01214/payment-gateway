package com.payment.auth.entity

enum class Provider {
    MOCK_VISA, MOCK_MASTERCARD, MOCK_GENERIC;
    fun toApiString(): String = name.lowercase().replace('_', '-')
}
